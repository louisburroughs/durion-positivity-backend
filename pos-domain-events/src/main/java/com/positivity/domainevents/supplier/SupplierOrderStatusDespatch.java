package com.positivity.domainevents.supplier;

import java.time.LocalDate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One actual despatch against a confirmed delivery schedule, carried by
 * {@link SupplierOrderStatusChangedV1} (durion-positivity-backend#1318).
 *
 * <p>A schedule may despatch several times, so this is the third and innermost level of the
 * status tree: line → schedule → despatch. Partial fulfilment is the modelled normal case,
 * not an edge case, which is why nothing here is flattened to "the" despatch of a line.
 *
 * <h2>The despatch advice reference is the ASN handle</h2>
 *
 * A despatch advice <em>is</em> the ASN (DESADV in EDIFACT terms), so {@code
 * despatchAdviceDocumentId} is the operator's handle for a carrier or vendor query about this
 * shipment — and the join key back to these rows if a real ASN feed is ever obtained from a
 * vendor. It is retained <strong>verbatim as an attribute</strong>: never a key, never parsed
 * for meaning (ADR-0013/0027). Vendors encode dates, plants and sequence numbers into these
 * strings in vendor-specific ways, and a consumer that reads structure out of one is reading a
 * convention no norm guarantees.
 *
 * <h2>Absence is not zero</h2>
 *
 * Every field is separately nullable and none is defaulted. A despatch the vendor stated
 * without a quantity is not a despatch of zero, and an absent despatch date is not today.
 *
 * @param despatchDate when the goods left the vendor, as the vendor stated it; null when the
 *     vendor stated no date
 * @param despatchAdviceDocumentId the despatch advice / ASN document reference, verbatim
 * @param despatchAdviceLineId the line within that despatch advice, when the vendor stated one
 * @param despatchedQuantity quantity actually despatched; null when the vendor stated none
 */
public record SupplierOrderStatusDespatch(
        @Nullable LocalDate despatchDate,
        @Nullable String despatchAdviceDocumentId,
        @Nullable String despatchAdviceLineId,
        @Nullable Integer despatchedQuantity) {

    /** Whether this despatch carries an ASN handle an operator could quote to the vendor. */
    public boolean hasDespatchAdviceReference() {
        return despatchAdviceDocumentId != null && !despatchAdviceDocumentId.isBlank();
    }

    /** A despatch with only a date and quantity, for consumers building expectations in tests. */
    @NonNull
    public static SupplierOrderStatusDespatch of(@Nullable LocalDate despatchDate, @Nullable Integer quantity) {
        return new SupplierOrderStatusDespatch(despatchDate, null, null, quantity);
    }
}
