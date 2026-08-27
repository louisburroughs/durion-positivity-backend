package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.WorkorderCommandPublisher;
import com.positivity.accounting.internal.entity.InvoiceRegenerationRequest;
import com.positivity.accounting.internal.repository.InvoiceRegenerationRequestRepository;
import com.positivity.accounting.service.InvoiceRegenerationService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import java.time.Clock;
import java.time.Instant;
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
 */
@Service
public class InvoiceRegenerationServiceImpl implements InvoiceRegenerationService {

    /** Response status for the async command path (ADR-0044 R4 pending state). */
    public static final String STATUS_PENDING = InvoiceRegenerationRequest.STATUS_PENDING;

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(InvoiceRegenerationServiceImpl.class);

    private final ObjectProvider<WorkorderCommandPublisher> commandPublisher;
    private final InvoiceRegenerationRequestRepository invoiceRegenerationRequestRepository;
    private final Clock clock;

    public InvoiceRegenerationServiceImpl(
            ObjectProvider<WorkorderCommandPublisher> commandPublisher,
            InvoiceRegenerationRequestRepository invoiceRegenerationRequestRepository,
            Clock clock) {
        this.commandPublisher = commandPublisher;
        this.invoiceRegenerationRequestRepository = invoiceRegenerationRequestRepository;
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
            if (existing.isPresent()
                    && InvoiceRegenerationRequest.STATUS_COMPLETED.equals(
                            existing.get().getStatus())) {
                return terminalResponse(workorderId, existing.get());
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
                .build());
        return InvoiceGenerationResponse.builder()
                .workorderId(workorderId)
                .status(STATUS_PENDING)
                .build();
    }

    private String maskWorkorderId(UUID workorderId) {
        if (workorderId == null) {
            return "null";
        }

        String value = workorderId.toString();
        return value.substring(0, 8) + "...";
    }
}
