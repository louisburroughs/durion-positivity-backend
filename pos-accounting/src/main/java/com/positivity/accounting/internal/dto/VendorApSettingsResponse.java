package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What stays accounting's about a vendor (CAP:550 S24, #2517; AW23, AW39): its AP defaults and the confirmation of a
 * changed remit-to. Every field is null until written.
 */
@Schema(description = "The vendor's accounting-side settings: AP defaults (AW39) and the remit-to confirmation")
public record VendorApSettingsResponse(
        @Schema(
                description = "GOODS or EXPENSE: the class a bill whose lines are not stored posts with when"
                        + " neither the approver nor the submission names one",
                example = "EXPENSE",
                allowableValues = {"GOODS", "EXPENSE"},
                requiredMode = NOT_REQUIRED)
        @Nullable
        VendorBillDebitClass defaultDebitClass,

        @Schema(
                description = "An active VENDOR_BILL expense key EXPENSE_<CODE>, used for EXPENSE and non-stock"
                        + " lines when nobody names one",
                example = "EXPENSE_SHOP_SUPPLIES",
                requiredMode = NOT_REQUIRED)
        @Nullable
        String defaultExpenseMappingKey,

        @Schema(
                description = "The remit-to version last confirmed; payment by anyone but the confirmer passes"
                        + " on it while it is current",
                example = "3",
                requiredMode = NOT_REQUIRED)
        @Nullable
        Integer confirmedRemitToVersion,

        @Schema(
                description = "Who confirmed it (principal name)",
                example = "q.controller",
                requiredMode = NOT_REQUIRED)
        @Nullable
        String remitToConfirmedBy,

        @Schema(description = "When it was confirmed", requiredMode = NOT_REQUIRED) @Nullable
        Instant remitToConfirmedAt) {

    /** Nothing written yet. */
    public static final VendorApSettingsResponse NONE = new VendorApSettingsResponse(null, null, null, null, null);
}
