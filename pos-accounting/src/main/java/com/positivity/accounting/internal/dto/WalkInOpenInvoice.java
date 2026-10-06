package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.WalkInResolution;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One walk-in (CASH) invoice with a balance still due (#2508). Business references come first; ids are
 * carried for links only (ADR-0064). Display fields are null when the replica does not know them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A walk-in sale on the CASH account with a balance still due")
public class WalkInOpenInvoice {

    @Schema(
            description = "Invoice number; null when the invoice replica has none",
            example = "INV-2026-01042",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String invoiceNumber;

    @Schema(
            description = "Code of the location that made the sale; null when unknown",
            example = "LOC-107",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String locationCode;

    @Schema(
            description = "Local date of the sale in its location's time zone (UTC when timezoneFallback is true)",
            example = "2026-10-05",
            requiredMode = REQUIRED)
    private LocalDate saleDate;

    @Schema(description = "Invoice total", example = "40.00", requiredMode = REQUIRED)
    private BigDecimal total;

    @Schema(
            description = "Balance still due after payment applications, credit memos and credits",
            example = "40.00",
            requiredMode = REQUIRED)
    private BigDecimal balanceDue;

    @Schema(
            description = "True when the sale's business day has ended at its location: the invoice needs attention",
            example = "true",
            requiredMode = REQUIRED)
    private boolean businessDayEnded;

    @Schema(
            description = "True when the location has no time zone (or the invoice no location), so day end was"
                    + " computed in UTC",
            example = "false",
            requiredMode = REQUIRED)
    private boolean timezoneFallback;

    @ArraySchema(
            arraySchema =
                    @Schema(
                            description = "How the invoice can be resolved: COLLECT and CREDIT_MEMO; reassigning it to"
                                    + " the real customer awaits a decision (OI-5)"),
            schema = @Schema(implementation = WalkInResolution.class))
    private List<WalkInResolution> resolutions;

    @Schema(
            description = "Invoice identifier, for links",
            example = "0199a000-0000-7000-8000-000000001702",
            requiredMode = REQUIRED)
    private UUID invoiceId;

    @Schema(
            description = "Location identifier, for links; null when the invoice has none",
            example = "0198a000-0000-7000-8000-000000000107",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private UUID locationId;
}
