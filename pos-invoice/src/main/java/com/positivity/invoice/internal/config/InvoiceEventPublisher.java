package com.positivity.invoice.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.invoice.BillingRulesUpdatedV1;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import com.positivity.domainevents.invoice.TaxBreakdownLine;
import com.positivity.invoice.internal.entity.BillingRules;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.InvoiceItem;
import com.positivity.invoice.internal.entity.InvoiceLineTax;
import com.positivity.invoice.internal.repository.InvoiceLineTaxRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Emits {@code invoice.invoice.updated} to the invoice outbox after document mutations
 * (ADR-0044, #842).
 *
 * <p>No-op when the Kafka feature flag ({@code pos.invoice.kafka.enabled}) is off — the
 * {@link OutboxEventWriter} bean is conditional, so this publisher degrades gracefully. Must be
 * called inside the mutating transaction (the writer requires {@code MANDATORY} propagation).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceEventPublisher {

    private final Clock clock;
    private final EntityManager entityManager;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final InvoiceLineTaxRepository invoiceLineTaxRepository;

    public void publishInvoiceUpdated(@NonNull Invoice invoice) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        // Flush so the JPA @Version already carries the committed value: versions are then
        // strictly increasing per invoice (create=0, updates=1,2,...), which the consumer's
        // stale-event guard relies on (PR #850 review).
        entityManager.flush();
        InvoiceUpdatedV1 payload = new InvoiceUpdatedV1(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getWorkorderId(),
                invoice.getEstimateId(),
                invoice.getLocationId(),
                invoice.getPartyId(),
                invoice.getStatus().name(),
                invoice.getSubtotal(),
                invoice.getTax(),
                invoice.getTotal(),
                invoice.getAdjustmentsAmount(),
                invoice.getCreatedAt(),
                invoice.getFinalizedAt(),
                buildTaxBreakdown(invoice.getId()),
                buildLines(invoice),
                invoice.getDueDate(),
                invoice.getPaymentTermsCode());
        DomainEventEnvelope<InvoiceUpdatedV1> envelope = DomainEventEnvelope.of(
                InvoiceUpdatedV1.EVENT_TYPE,
                InvoiceUpdatedV1.SCHEMA_VERSION,
                invoice.getId(),
                invoice.getVersion() == null ? 0L : invoice.getVersion().longValue(),
                "pos-invoice",
                null,
                null,
                payload,
                clock);
        writer.publish(DomainTopics.events("invoice"), envelope);
        log.debug("Queued invoice.invoice.updated invoiceId={} status={}", invoice.getId(), invoice.getStatus());
    }

    /**
     * Projects the persisted {@code invoice_line_tax} rows onto the additive event breakdown
     * (story T5b).
     *
     * <p>Null-vs-empty contract (#982): this producer is authoritative over an invoice's tax
     * rows, so it emits the <em>actual</em> row set — including an empty list when the invoice
     * genuinely has none. The accounting listener treats an empty list as "clear the replica"
     * and {@code null} as "leave existing rows untouched"; emitting empty (not null) is what
     * lets a taxed&rarr;untaxed transition clear {@code ext_invoice_tax}. {@code null} is
     * reserved for the one case where no set can be projected at all: a null {@code invoiceId}.
     */
    @Nullable
    private List<TaxBreakdownLine> buildTaxBreakdown(@Nullable UUID invoiceId) {
        if (invoiceId == null) {
            return null;
        }
        List<InvoiceLineTax> rows = invoiceLineTaxRepository.findByInvoiceId(invoiceId);
        if (rows.isEmpty()) {
            // Authoritative empty set → clears the accounting replica on taxed→untaxed (#982).
            return List.of();
        }
        return rows.stream()
                .map(r -> new TaxBreakdownLine(
                        r.getLineItemId(),
                        r.getJurisdictionType(),
                        r.getJurisdictionCode(),
                        r.getRate(),
                        r.getTaxableBase(),
                        r.getTaxAmount(),
                        r.isExempt(),
                        r.getExemptionReasonCode()))
                .toList();
    }

    /**
     * Projects the invoice's line items onto the additive event line detail (#924) that feeds
     * pos-warranty's line-grained {@code ext_invoice} replica. Authoritative-empty vs. null,
     * matching {@link #buildTaxBreakdown}: an empty list is a genuine no-line invoice.
     */
    private List<InvoiceUpdatedV1.InvoiceLine> buildLines(@NonNull Invoice invoice) {
        List<InvoiceItem> items = invoice.getItems();
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(item -> new InvoiceUpdatedV1.InvoiceLine(
                        item.getId(),
                        item.getDescription(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getLineTotal(),
                        item.getWorkorderItemId(),
                        item.getType()))
                .toList();
    }

    /** Emits {@code invoice.billing-rules.updated} after a rules mutation (ADR-0044, #902). */
    public void publishBillingRulesUpdated(@NonNull BillingRules rules) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        // Same flush rationale as invoices: the JPA @Version must carry the committed value so
        // the consumer's stale-event guard sees strictly increasing versions per party.
        entityManager.flush();
        BillingRulesUpdatedV1 payload = new BillingRulesUpdatedV1(
                rules.getPartyId(),
                rules.isPurchaseOrderRequired(),
                rules.getPaymentTermsCode(),
                rules.getInvoiceDeliveryMethod() == null
                        ? null
                        : rules.getInvoiceDeliveryMethod().name(),
                rules.getInvoiceGroupingStrategy() == null
                        ? null
                        : rules.getInvoiceGroupingStrategy().name(),
                rules.isFleetAuthorizationRequired(),
                rules.getFleetSupplierRef());
        DomainEventEnvelope<BillingRulesUpdatedV1> envelope = DomainEventEnvelope.of(
                BillingRulesUpdatedV1.EVENT_TYPE,
                BillingRulesUpdatedV1.SCHEMA_VERSION,
                billingRulesAggregateId(rules.getPartyId()),
                rules.getVersion() == null ? 0L : rules.getVersion().longValue(),
                "pos-invoice",
                null,
                null,
                payload,
                clock);
        writer.publish(DomainTopics.events("invoice"), envelope);
        log.debug(
                "Queued invoice.billing-rules.updated partyId(hash)={}",
                rules.getPartyId().hashCode());
    }

    /** partyId is stored as a 36-char string; parse when it is a UUID, else derive one stably. */
    private static UUID billingRulesAggregateId(@NonNull String partyId) {
        try {
            return UUID.fromString(partyId);
        } catch (IllegalArgumentException _) {
            return UUID.nameUUIDFromBytes(partyId.getBytes(StandardCharsets.UTF_8));
        }
    }
}
