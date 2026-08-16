package com.positivity.domainevents.supplier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A vendor AR document, published on {@code supplier.events.v1} (CAP-321 #1227, ADR-0049 §3).
 *
 * <h2>What this is and is not</h2>
 *
 * It is what the vendor said it is owed, transcribed. It is not a posting instruction: what accounts
 * it touches, when it hits the ledger and whether anybody has agreed to pay it are pos-accounting's,
 * and this fact deliberately says nothing about them. pos-supplier's scope ends here.
 *
 * <h2>Identity, because the same invoice arrives more than once</h2>
 *
 * Fetch windows overlap on re-run by design (ADR-0052 §5) — that is what makes a failed fetch safe
 * to repeat. So the same document is republished, and the consumer must key on
 * {@code (vendorProfileId, vendorInvoiceNumber, invoiceDate)} rather than on the event. An AP ledger
 * that counted a redelivery as a second debt would pay twice.
 *
 * <h2>Amounts as stated</h2>
 *
 * Totals are the vendor's own, not derived from the lines, and may disagree with them. That
 * disagreement is information — it is the sort of thing an AP clerk is employed to notice — and
 * silently reconciling it here would destroy the evidence.
 *
 * @param vendorProfileId       the vendor profile the document was fetched from
 * @param supplierRef           that profile's alias at fetch time; descriptive, never a key
 * @param vendorInvoiceNumber   the vendor's own document number
 * @param invoiceDate           the vendor's issue date
 * @param type                  {@code INVOICE} for money owed to the vendor, {@code CREDIT_NOTE}
 *                              for money owed back. Never inferred from the sign of an amount
 * @param currency              ISO 4217 code as stated; an amount without one is not a sum of money
 * @param totalNetAmount        taxable amount as stated, when given
 * @param totalTaxAmount        tax amount as stated, when given
 * @param totalGrossAmount      the total the vendor expects to be paid, when given
 * @param vendorOrderReference  the vendor's order reference, where it gave one. Resolving it to a
 *                              purchase order is the consumer's job; an unresolvable reference is
 *                              not a reason to withhold the invoice
 * @param occurredAt            when the document was fetched
 * @param lines                 the document's lines, as stated
 */
public record SupplierInvoiceReceivedV1(
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        @NonNull String vendorInvoiceNumber,
        @NonNull LocalDate invoiceDate,
        @NonNull Type type,
        @NonNull String currency,
        @Nullable BigDecimal totalNetAmount,
        @Nullable BigDecimal totalTaxAmount,
        @Nullable BigDecimal totalGrossAmount,
        @Nullable String vendorOrderReference,
        @NonNull Instant occurredAt,
        @NonNull List<SupplierInvoiceLine> lines) {

    /** Event type of this payload on {@code supplier.events.v1}. */
    public static final String EVENT_TYPE = "supplier.invoice.received";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Which way the money runs.
     *
     * <p>An enum rather than a validated string, so a value outside these two cannot be constructed
     * at all. The direction of a payment is not something to discover at runtime.
     */
    public enum Type {
        /** Money owed to the vendor. */
        INVOICE,
        /** Money owed back by the vendor. */
        CREDIT_NOTE
    }

    public SupplierInvoiceReceivedV1 {
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(vendorInvoiceNumber, "vendorInvoiceNumber must not be null");
        Objects.requireNonNull(invoiceDate, "invoiceDate must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }

    /** Whether this document increases what is owed to the vendor. */
    public boolean isPayable() {
        return type == Type.INVOICE;
    }
}
