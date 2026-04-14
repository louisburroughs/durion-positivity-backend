package com.positivity.inventory.internal.service;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EncumbranceEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Retryable(maxAttempts = 3)
    public void publishEncumbranceEvent(
            @NonNull UUID purchaseOrderId, @NonNull String poNumber, long grandTotalMinor, @NonNull String currency) {
        log.info("Publishing encumbrance event for PO {}", purchaseOrderId);
        applicationEventPublisher.publishEvent(Map.of(
                "type", "PurchaseOrderEncumbrance",
                "purchaseOrderId", purchaseOrderId.toString(),
                "poNumber", poNumber,
                "grandTotalMinor", grandTotalMinor,
                "currency", currency));
    }

    @Recover
    public void recoverEncumbranceEvent(
            @NonNull Exception e,
            @NonNull UUID purchaseOrderId,
            @NonNull String poNumber,
            long grandTotalMinor,
            @NonNull String currency) {
        log.error("Encumbrance event publishing failed after retries for PO {}: {}", purchaseOrderId, e.getMessage());
        applicationEventPublisher.publishEvent(Map.of(
                "type",
                "PurchaseOrderAccountingError",
                "purchaseOrderId",
                purchaseOrderId.toString(),
                "poNumber",
                poNumber,
                "grandTotalMinor",
                grandTotalMinor,
                "currency",
                currency,
                "error",
                e.getMessage() != null ? e.getMessage() : "Unknown error"));
    }
}
