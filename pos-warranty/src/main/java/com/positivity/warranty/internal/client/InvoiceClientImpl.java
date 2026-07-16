package com.positivity.warranty.internal.client;

import com.positivity.warranty.internal.exception.WarrantyIntegrationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * RestClient implementation of {@link InvoiceClient}.
 *
 * <p>Reads and adjustment writes demand authority {@code invoice:manage}. The refund endpoint
 * additionally enforces {@code REFUND_PAYMENT} in the pos-invoice service layer plus
 * {@code SUPERVISOR_OVERRIDE} for payments outside pos-invoice's 180-day refund window —
 * warranty refunds routinely reverse payments captured years earlier, and the warranty flow's
 * own approval gate (claim must be APPROVED before settlement) is the compensating control.
 */
@Slf4j
@Component
public class InvoiceClientImpl implements InvoiceClient {

    private static final String SERVICE_USER = "pos-warranty-service";
    private static final String AUTHORITIES = "invoice:manage";
    private static final String REFUND_AUTHORITIES = "invoice:manage,REFUND_PAYMENT,SUPERVISOR_OVERRIDE";
    private static final String REFUND_STATUS_COMPLETED = "COMPLETED";
    private static final String ADJUSTMENT_TYPE_WARRANTY = "WARRANTY";
    private static final String REFUND_REASON_OTHER = "OTHER";

    private final RestClient restClient;
    private final String basePath;

    public InvoiceClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.invoice.service-id:invoice}") String serviceId,
            @Value("${pos.invoice.base-path:/v1/invoices}") String basePath) {
        this.basePath = basePath;
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    @Override
    public @NonNull List<InvoiceLine> searchInvoiceLines(@NonNull UUID partyId) {
        try {
            List<InvoiceLine> lines = restClient
                    .get()
                    .uri(basePath + "/items/search?partyId={partyId}", partyId)
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", AUTHORITIES)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<InvoiceLine>>() {});
            return lines != null ? lines : List.of();
        } catch (RestClientResponseException ex) {
            log.warn(
                    "Invoice line search failed for partyId={}: HTTP {}",
                    partyId,
                    ex.getStatusCode().value());
            return List.of();
        } catch (RestClientException ex) {
            log.warn("pos-invoice unreachable for line search partyId={}: {}", partyId, ex.getMessage());
            return List.of();
        }
    }

    @Override
    public @NonNull Optional<InvoiceSummary> getInvoice(@NonNull UUID invoiceId) {
        try {
            InvoiceDetailsWire response = restClient
                    .get()
                    .uri(basePath + "/{invoiceId}", invoiceId)
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", AUTHORITIES)
                    .retrieve()
                    .body(InvoiceDetailsWire.class);
            if (response == null) {
                return Optional.empty();
            }
            List<InvoiceItem> items = response.items() == null
                    ? List.of()
                    : response.items().stream()
                            .map(item -> new InvoiceItem(
                                    item.id(),
                                    item.description(),
                                    item.quantity(),
                                    item.unitPrice(),
                                    item.amount(),
                                    item.workorderItemId()))
                            .toList();
            return Optional.of(new InvoiceSummary(
                    response.invoiceId(),
                    response.invoiceNumber(),
                    response.partyId(),
                    response.status(),
                    response.total(),
                    items));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                log.debug("Invoice {} not found", invoiceId);
            } else {
                log.warn(
                        "Invoice lookup failed for invoiceId={}: HTTP {}",
                        invoiceId,
                        ex.getStatusCode().value());
            }
            return Optional.empty();
        } catch (RestClientException ex) {
            log.warn("pos-invoice unreachable for invoiceId={}: {}", invoiceId, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public @NonNull Optional<UUID> createAdjustment(
            @NonNull UUID invoiceId,
            @NonNull BigDecimal amount,
            @NonNull String reason,
            @NonNull String authorizedBy,
            String externalReference) {
        AdjustmentRequestWire body =
                new AdjustmentRequestWire(ADJUSTMENT_TYPE_WARRANTY, amount, reason, authorizedBy, externalReference);
        InvoiceDetailsWire response;
        try {
            response = restClient
                    .post()
                    .uri(basePath + "/{invoiceId}/adjustments", invoiceId)
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", AUTHORITIES)
                    .body(body)
                    .retrieve()
                    .body(InvoiceDetailsWire.class);
        } catch (RestClientException ex) {
            throw new WarrantyIntegrationException(
                    "Failed to create warranty adjustment on invoice " + invoiceId + ": " + ex.getMessage(), ex);
        }
        if (response == null || response.adjustmentEntries() == null) {
            log.warn("Adjustment applied on invoice {} but response exposed no adjustment entries", invoiceId);
            return Optional.empty();
        }
        // Prefer the entry carrying our externalReference; fall back to the newest
        // WARRANTY entry (the one this call just created on a success response).
        Optional<UUID> byReference = response.adjustmentEntries().stream()
                .filter(entry -> externalReference != null && externalReference.equals(entry.externalReference()))
                .map(AdjustmentEntryWire::id)
                .findFirst();
        if (byReference.isPresent()) {
            return byReference;
        }
        return response.adjustmentEntries().stream()
                .filter(entry -> ADJUSTMENT_TYPE_WARRANTY.equals(entry.type()))
                .max(Comparator.comparing(
                        AdjustmentEntryWire::createdAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(AdjustmentEntryWire::id)
                .filter(Objects::nonNull);
    }

    @Override
    public @NonNull Optional<UUID> createRefund(
            @NonNull UUID invoiceId,
            @NonNull UUID paymentId,
            @NonNull BigDecimal amount,
            String notes,
            String externalReference) {
        RefundRequestWire body = new RefundRequestWire(amount, REFUND_REASON_OTHER, notes, externalReference);
        RefundResponseWire response;
        try {
            response = restClient
                    .post()
                    .uri(basePath + "/{invoiceId}/payments/{paymentId}/refunds", invoiceId, paymentId)
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", REFUND_AUTHORITIES)
                    .body(body)
                    .retrieve()
                    .body(RefundResponseWire.class);
        } catch (RestClientException ex) {
            throw new WarrantyIntegrationException(
                    "Failed to create refund on invoice " + invoiceId + " payment " + paymentId + ": "
                            + ex.getMessage(),
                    ex);
        }
        if (response == null || response.refundId() == null) {
            log.warn("Refund created on invoice {} but response exposed no refundId", invoiceId);
            return Optional.empty();
        }
        // pos-invoice persists the RefundRecord and returns 201 even when the payment gateway
        // declines (status FAILED) — a refundId alone does not mean the customer was paid.
        if (!REFUND_STATUS_COMPLETED.equals(response.status())) {
            throw new WarrantyIntegrationException("Refund " + response.refundId() + " on invoice " + invoiceId
                    + " payment " + paymentId + " returned status " + response.status()
                    + "; customer was not refunded");
        }
        return Optional.of(response.refundId());
    }

    // -----------------------------------------------------------------------
    // Wire shapes (mirror the pos-invoice API contract; unknown properties ignored)
    // -----------------------------------------------------------------------

    private record AdjustmentRequestWire(
            String type, BigDecimal amount, String reason, String authorizedBy, String externalReference) {}

    private record RefundRequestWire(BigDecimal amount, String reason, String notes, String externalReference) {}

    private record RefundResponseWire(UUID refundId, UUID invoiceId, BigDecimal amount, String status) {}

    private record InvoiceDetailsWire(
            UUID invoiceId,
            String invoiceNumber,
            String partyId,
            String status,
            BigDecimal total,
            List<InvoiceItemWire> items,
            List<AdjustmentEntryWire> adjustmentEntries) {}

    private record InvoiceItemWire(
            UUID id,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal amount,
            UUID workorderItemId,
            String type) {}

    private record AdjustmentEntryWire(
            UUID id, String type, BigDecimal amount, String externalReference, Instant createdAt) {}
}
