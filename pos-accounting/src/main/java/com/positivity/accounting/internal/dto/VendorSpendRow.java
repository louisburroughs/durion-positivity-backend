package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One vendor's spend over the report window (Wave 2 E8, issue #1596).
 *
 * <p>{@code paidAmount} and {@code billsIssuedInWindow}/{@code avgIssuedBillAmount} are two
 * different populations, not two views of the same set of bills: {@code paidAmount} sums settled
 * A/P cash ({@code APPayment.grossAmount} for payments whose {@code paymentDate} falls in the
 * window and whose status shows the gateway already moved the cash — {@code GATEWAY_SUCCEEDED} or
 * later; a payment can allocate against bills issued in an earlier or later window than this one).
 * {@code billsIssuedInWindow} and {@code avgIssuedBillAmount} count and average every {@code
 * VendorBill} whose {@code billDate} falls in the window, regardless of payment status. A caller
 * must not assume {@code avgIssuedBillAmount * billsIssuedInWindow} reconciles to {@code
 * paidAmount}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(
        description = "One vendor's spend over the report window; see field descriptions for the paidAmount vs"
                + " billsIssuedInWindow/avgIssuedBillAmount population split")
public class VendorSpendRow {

    @Schema(
            description = "Vendor identifier",
            example = "01936e5b-4567-7a3d-8b6e-1a2345678901",
            requiredMode = REQUIRED)
    private UUID vendorId;

    @Schema(
            description = "Resolved vendor display name, from the AP vendor directory; falls back to the vendor"
                    + " name snapshot recorded on the vendor's own bills/payments when the vendor has no"
                    + " directory entry, and is null only if neither source has a name",
            example = "Acme Parts Co",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String name;

    @Schema(
            description = "Sum of APPayment.grossAmount for settled payments (status GATEWAY_SUCCEEDED or later)"
                    + " to this vendor whose paymentDate falls in the window; 0 when none settled in the window."
                    + " This is A/P cash, a DIFFERENT population from billsIssuedInWindow/avgIssuedBillAmount"
                    + " below — see class Javadoc.",
            example = "18250.00",
            requiredMode = REQUIRED)
    private BigDecimal paidAmount;

    @Schema(
            description = "Count of VendorBill records for this vendor whose billDate falls in the window,"
                    + " regardless of payment status; 0 when none issued in the window. Bill-side figure, a"
                    + " DIFFERENT population from paidAmount above — see class Javadoc.",
            example = "4",
            requiredMode = REQUIRED)
    private int billsIssuedInWindow;

    @Schema(
            description = "Sum of VendorBill.totalAmount for this vendor's bills in the window divided by"
                    + " billsIssuedInWindow; 0 (never null) when billsIssuedInWindow is 0 — there is nothing to"
                    + " average, and 0 keeps this field a well-defined BigDecimal for every row",
            example = "1425.75",
            requiredMode = REQUIRED)
    private BigDecimal avgIssuedBillAmount;
}
