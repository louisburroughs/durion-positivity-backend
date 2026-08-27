package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.WorkorderCommandPublisher;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.InvoiceRegenerationRequest;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.InvoiceRegenerationRequestRepository;
import com.positivity.accounting.service.InvoiceRegenerationService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Requests invoice regeneration from pos-workorder.
 *
 * <p>ADR-0044 (#842/#900): publishes a {@code workorder.invoice.regenerate-requested} command to
 * {@code workorder.commands.v1} and returns a {@code PENDING} response — the regenerated invoice
 * arrives asynchronously via {@code invoice.events.v1} into the {@code ext_invoice} replica. The
 * synchronous fallback client was removed in Phase 5.4 (#900); with the event feed disabled the
 * endpoint fails fast with 503.
 *
 * <p>#1537 D1: every publish persists a {@link InvoiceRegenerationRequest} tracking row keyed by
 * the publisher's commandId, which {@code WorkorderEventsListener} resolves to {@code COMPLETED}
 * once a {@code workorder.events.v1} fact for the workorder carries the resulting invoice id. A
 * repeat call carrying an idempotency key whose row already completed returns that terminal state
 * — {@code invoiceId} included — without publishing again; this needs no Kafka feed, so it is
 * checked before the feed-disabled fast-fail below.
 *
 * <p>#1537 F3: a repeat call whose row is still {@code PENDING} short-circuits the same way,
 * returning the existing pending state rather than publishing a second command. A client that
 * retries a slow-but-legitimate regeneration (its original request already wrote the row) would
 * otherwise fire a duplicate {@code workorder.invoice.regenerate-requested} command and then fail
 * the second {@code invoice_regeneration_request} insert against the partial unique index on
 * {@code idempotency_key} (V26) with a 5xx — not idempotent across the retry window a client would
 * actually use. Any existing row for the key — pending or completed — is therefore terminal for
 * this call; only a genuinely new idempotency key publishes.
 */
@Service
public class InvoiceRegenerationServiceImpl implements InvoiceRegenerationService {

    /** Response status for the async command path (ADR-0044 R4 pending state). */
    public static final String STATUS_PENDING = InvoiceRegenerationRequest.STATUS_PENDING;

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(InvoiceRegenerationServiceImpl.class);

    private final ObjectProvider<WorkorderCommandPublisher> commandPublisher;
    private final InvoiceRegenerationRequestRepository invoiceRegenerationRequestRepository;
    private final ExtInvoiceRepository extInvoiceRepository;
    private final Clock clock;

    public InvoiceRegenerationServiceImpl(
            ObjectProvider<WorkorderCommandPublisher> commandPublisher,
            InvoiceRegenerationRequestRepository invoiceRegenerationRequestRepository,
            ExtInvoiceRepository extInvoiceRepository,
            Clock clock) {
        this.commandPublisher = commandPublisher;
        this.invoiceRegenerationRequestRepository = invoiceRegenerationRequestRepository;
        this.extInvoiceRepository = extInvoiceRepository;
        this.clock = clock;
    }

    @Override
    @NonNull
    public InvoiceGenerationResponse regenerateInvoiceFromWorkorder(
            @NonNull UUID workorderId, @Nullable String idempotencyKey) {
        if (log.isInfoEnabled()) {
            log.info("Regenerating invoice from workorder {}", maskWorkorderId(workorderId));
        }

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<InvoiceRegenerationRequest> existing =
                    invoiceRegenerationRequestRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                InvoiceRegenerationRequest row = existing.get();
                if (!row.getWorkorderId().equals(workorderId)) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Idempotency key already used for a different workorder ("
                                    + maskWorkorderId(row.getWorkorderId()) + ")");
                }
                if (InvoiceRegenerationRequest.STATUS_COMPLETED.equals(row.getStatus())) {
                    return terminalResponse(workorderId, row);
                }
                if (InvoiceRegenerationRequest.STATUS_FAILED.equals(row.getStatus())) {
                    return failedResponse(workorderId);
                }
                return pendingResponse(workorderId);
            }
        }

        WorkorderCommandPublisher publisher = commandPublisher.getIfAvailable();
        if (publisher == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Invoice regeneration is asynchronous (ADR-0044 #900) and requires the Kafka event feed;"
                            + " enable pos.accounting.kafka.enabled");
        }
        return requestAsyncRegeneration(publisher, workorderId, idempotencyKey);
    }

    private InvoiceGenerationResponse terminalResponse(UUID workorderId, InvoiceRegenerationRequest completed) {
        return InvoiceGenerationResponse.builder()
                .workorderId(workorderId)
                .status(InvoiceRegenerationRequest.STATUS_COMPLETED)
                .invoiceId(completed.getResultInvoiceId())
                .build();
    }

    /**
     * #1537 F3: the short-circuit response for a still-outstanding request — the original
     * command (and its commandId) already published, so this call never re-publishes.
     */
    private InvoiceGenerationResponse pendingResponse(UUID workorderId) {
        return InvoiceGenerationResponse.builder()
                .workorderId(workorderId)
                .status(STATUS_PENDING)
                .build();
    }

    /**
     * Finding 2a: a row the reaper marked {@link InvoiceRegenerationRequest#STATUS_FAILED} must be
     * reported as failed, not silently downgraded to {@link #STATUS_PENDING} — a caller polling
     * this idempotency key would otherwise wait forever for a regeneration that already gave up.
     */
    private InvoiceGenerationResponse failedResponse(UUID workorderId) {
        return InvoiceGenerationResponse.builder()
                .workorderId(workorderId)
                .status(InvoiceRegenerationRequest.STATUS_FAILED)
                .build();
    }

    private InvoiceGenerationResponse requestAsyncRegeneration(
            WorkorderCommandPublisher publisher, UUID workorderId, @Nullable String idempotencyKey) {
        String requestedBy = SecurityContextHelper.getCurrentUsernameOrDefault("SYSTEM");
        UUID commandId;
        try {
            commandId = publisher.requestInvoiceRegeneration(workorderId, idempotencyKey, requestedBy);
        } catch (Exception e) {
            log.error("Failed to queue invoice regeneration command for {}", maskWorkorderId(workorderId), e);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to queue invoice regeneration for workorder " + workorderId,
                    e);
        }
        invoiceRegenerationRequestRepository.save(InvoiceRegenerationRequest.builder()
                .workorderId(workorderId)
                .commandId(commandId)
                .idempotencyKey(idempotencyKey)
                .status(InvoiceRegenerationRequest.STATUS_PENDING)
                .requestedBy(requestedBy)
                .requestedAt(Instant.now(clock))
                .priorInvoiceId(resolvePriorInvoiceId(workorderId))
                .build());
        return InvoiceGenerationResponse.builder()
                .workorderId(workorderId)
                .status(STATUS_PENDING)
                .build();
    }

    /**
     * The invoiceId already linked to {@code workorderId}, if any (#1537 F4) — captured at
     * request time so {@code WorkorderEventsListener} can tell a genuinely new invoice fact
     * apart from an unrelated update that merely echoes the invoice the requester already had.
     * When more than one replicated invoice references the workorder, the most recently updated
     * one is the workorder's current invoice.
     */
    private @Nullable UUID resolvePriorInvoiceId(UUID workorderId) {
        List<ExtInvoice> existing = extInvoiceRepository.findByWorkorderId(workorderId);
        return existing.stream()
                .max(Comparator.comparing(ExtInvoice::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ExtInvoice::getInvoiceId)
                .orElse(null);
    }

    private String maskWorkorderId(UUID workorderId) {
        if (workorderId == null) {
            return "null";
        }

        String value = workorderId.toString();
        return value.substring(0, 8) + "...";
    }
}
