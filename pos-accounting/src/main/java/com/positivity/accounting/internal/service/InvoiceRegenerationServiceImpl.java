package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.WorkorderCommandPublisher;
import com.positivity.accounting.service.InvoiceRegenerationService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
 */
@Service
@RequiredArgsConstructor
public class InvoiceRegenerationServiceImpl implements InvoiceRegenerationService {

    /** Response status for the async command path (ADR-0044 R4 pending state). */
    public static final String STATUS_PENDING = "PENDING";

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(InvoiceRegenerationServiceImpl.class);

    private final ObjectProvider<WorkorderCommandPublisher> commandPublisher;

    @Override
    @NonNull
    public InvoiceGenerationResponse regenerateInvoiceFromWorkorder(
            @NonNull UUID workorderId, @Nullable String idempotencyKey) {
        if (log.isInfoEnabled()) {
            log.info("Regenerating invoice from workorder {}", maskWorkorderId(workorderId));
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

    private InvoiceGenerationResponse requestAsyncRegeneration(
            WorkorderCommandPublisher publisher, UUID workorderId, @Nullable String idempotencyKey) {
        try {
            publisher.requestInvoiceRegeneration(
                    workorderId, idempotencyKey, SecurityContextHelper.getCurrentUsernameOrDefault("SYSTEM"));
        } catch (Exception e) {
            log.error("Failed to queue invoice regeneration command for {}", maskWorkorderId(workorderId), e);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to queue invoice regeneration for workorder " + workorderId,
                    e);
        }
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
