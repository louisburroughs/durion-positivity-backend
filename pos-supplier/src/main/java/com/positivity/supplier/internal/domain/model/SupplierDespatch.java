package com.positivity.supplier.internal.domain.model;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * One actual despatch against a confirmed delivery schedule (CAP-320 #1318).
 *
 * <p>Canonical, not vendor-shaped: the EDIWheel Order Status C1.1 document nests these under
 * {@code ScheduleDetails/ShippedDetails/DespatchedDetails/ScheduledArticleDespatchDetails}, and
 * that nesting is a wire concern the codec flattens away. What survives is the fact — goods
 * left, on a date, in a quantity, under a despatch advice reference.
 *
 * <h2>{@code despatchAdviceDocumentId} is the ASN handle, kept verbatim</h2>
 *
 * A despatch advice <em>is</em> the ASN (DESADV in EDIFACT terms), so this string is the
 * operator's handle for a carrier or vendor query — and the join key back to these rows if a
 * real ASN feed ever appears. It is retained exactly as the vendor stated it and is never a key
 * and never parsed for meaning (ADR-0013/0027): vendors encode plants, dates and sequences into
 * these strings by private convention, and reading structure out of one is reading a convention
 * no norm guarantees.
 *
 * @param despatchDate when the goods left the vendor, as stated; null when the vendor stated no
 *     date. Never defaulted to the delivery date or to today — an undated despatch is a
 *     despatch whose date we do not know
 * @param despatchAdviceDocumentId the despatch advice / ASN document reference, verbatim
 * @param despatchAdviceLineId the line within that despatch advice, when stated
 * @param despatchedQuantity quantity despatched; null when the vendor stated none, which is not
 *     a despatch of zero
 */
public record SupplierDespatch(
        @Nullable LocalDate despatchDate,
        @Nullable String despatchAdviceDocumentId,
        @Nullable String despatchAdviceLineId,
        @Nullable Integer despatchedQuantity) {}
