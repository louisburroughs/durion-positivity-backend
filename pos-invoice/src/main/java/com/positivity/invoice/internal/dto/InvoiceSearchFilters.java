package com.positivity.invoice.internal.dto;

import com.positivity.invoice.internal.enums.InvoiceStatus;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Structured filters for {@code GET /v1/invoices/search} (#1599, E11), combinable with each
 * other and with the free-text {@code q} term.
 *
 * <p>{@code issuedFrom}/{@code issuedTo} are anchored on {@code Invoice.finalizedAt} — the
 * timestamp an invoice was finalized/issued to the customer, frozen at finalization alongside
 * {@code dueDate}. A {@code DRAFT} invoice has no {@code finalizedAt} yet, so it is excluded
 * from any result set whenever either bound is set; {@code Invoice.createdAt} (draft-creation)
 * is deliberately not used here since it reports when the invoice was first opened, not when it
 * was actually issued.
 *
 * @param status exact invoice status match
 * @param issuedFrom window start (inclusive), evaluated against {@code Invoice.finalizedAt}
 * @param issuedTo window end (inclusive), evaluated against {@code Invoice.finalizedAt}
 * @param customerId exact customer (party) id match
 */
public record InvoiceSearchFilters(
        @Nullable InvoiceStatus status,
        @Nullable LocalDate issuedFrom,
        @Nullable LocalDate issuedTo,
        @Nullable String customerId) {

    /** Sentinel for "no structured filter supplied" — every field absent. */
    public static final InvoiceSearchFilters NONE = new InvoiceSearchFilters(null, null, null, null);

    /**
     * @return true when none of the structured filters are set, i.e. this instance is
     *     equivalent to {@link #NONE}
     */
    public boolean isEmpty() {
        return status == null && issuedFrom == null && issuedTo == null && customerId == null;
    }
}
