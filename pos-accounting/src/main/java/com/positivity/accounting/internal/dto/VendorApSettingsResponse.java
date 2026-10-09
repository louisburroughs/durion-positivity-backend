package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What stays accounting's about a vendor (CAP:550 S24, #2517; AW23, AW39): its AP defaults, the confirmation of a
 * changed remit-to, its AP payment hold, its information-return reportable flag (#2615) and whether it accepts tax on
 * goods for resale (S43). Every default is null
 * until written; without a row the vendor is not held and not reportable.
 */
@Schema(
        description = "The vendor's accounting-side settings: AP defaults (AW39), the remit-to confirmation, the AP"
                + " payment hold and the information-return flag")
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

        @Schema(
                description = "The display name of the person who confirmed it (\"First Last\"), resolved now from"
                        + " accounting's people-contact copy; null when not known, never the sign-in name",
                example = "Dana Reyes",
                requiredMode = NOT_REQUIRED)
        @Nullable
        String remitToConfirmedByName,

        @Schema(description = "When it was confirmed", requiredMode = NOT_REQUIRED) @Nullable
        Instant remitToConfirmedAt,

        @Schema(description = "The AP payment hold; onHold false without a hold", requiredMode = REQUIRED)
        ApHold apHold,

        @Schema(
                description = "The information-return reportable flag; reportable false when not set",
                requiredMode = REQUIRED)
        InformationReturn informationReturn,

        @Schema(
                description = "Whether a bill of this vendor charging tax on goods for resale is approved without a"
                        + " per-bill override where the tax country's purchase-tax rules hold such bills (CAP:550"
                        + " S43); false when not set",
                example = "false",
                requiredMode = REQUIRED)
        boolean acceptTaxOnResaleGoods) {

    /** Nothing written yet: no defaults, not held, not reportable. */
    public static final VendorApSettingsResponse NONE = new VendorApSettingsResponse(
            null, null, null, null, null, null, ApHold.NONE, InformationReturn.NONE, false);

    /**
     * The AP payment hold (#2615). The reason is staff free text handled as CONFIDENTIAL (ADR-0072): served here, never
     * logged.
     */
    @Schema(
            name = "VendorApHold",
            description = "The vendor's AP payment hold: while onHold is true, POST /v1/accounting/ap/payments for the"
                    + " vendor answers 422 VENDOR_ON_AP_HOLD; bills are still approved and posted")
    public record ApHold(
            @Schema(
                    description = "Whether AP payments to the vendor are held",
                    example = "true",
                    requiredMode = REQUIRED)
            boolean onHold,

            @Schema(
                    description = "Why the vendor is held; null without a hold",
                    example = "Disputed delivery 4471, awaiting credit",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String reason,

            @Schema(
                    description = "Who set the hold or last changed its reason (principal name); null without a hold",
                    example = "q.controller",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String setBy,

            @Schema(
                    description = "The display name of the person who set the hold (\"First Last\"), resolved now from"
                            + " accounting's people-contact copy; null when not known or without a hold, never the"
                            + " sign-in name",
                    example = "Dana Reyes",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String setByName,

            @Schema(
                    description = "When the hold was set or its reason last changed; null without a hold",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            Instant setAt) {

        /** Not held. */
        public static final ApHold NONE = new ApHold(false, null, null, null, null);

        @Override
        public String toString() {
            // The reason and setByName are CONFIDENTIAL (ADR-0072): never printed.
            return "ApHold[onHold=" + onHold + ", setBy=" + setBy + ", setAt=" + setAt + "]";
        }
    }

    /**
     * The information-return reportable flag (#2615), with the payee's tax number shown only as on file or not and its
     * copied last four characters. {@code payeeTinLast4} is CONFIDENTIAL (ADR-0072): served in the vendor read only.
     */
    @Schema(
            name = "VendorInformationReturn",
            description = "Whether the vendor is reportable on the tax country's information return, in which form and"
                    + " box, and whether the payee's tax number is on file in pos-supplier")
    public record InformationReturn(
            @Schema(description = "Whether the vendor is reportable", example = "true", requiredMode = REQUIRED)
            boolean reportable,

            @Schema(
                    description = "The configured form code; null when not reportable",
                    example = "ZZ_FORM_A",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String form,

            @Schema(
                    description = "The form's box code; null when not reportable",
                    example = "1",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String box,

            @Schema(
                    description = "The payee-id scheme the payee is reported under; null when none was chosen",
                    example = "ZZ_BUSINESS_ID",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String payeeTaxRegistrationScheme,

            @Schema(
                    description = "true when the vendor's copy holds at least one tax registration of the chosen"
                            + " scheme; false without one or without a chosen scheme. The number is entered in"
                            + " pos-supplier",
                    example = "true",
                    requiredMode = REQUIRED)
            boolean payeeTinOnFile,

            @Schema(
                    description = "The last four characters pos-supplier computed for the one registration of the"
                            + " chosen scheme; null when there is none, more than one, or pos-supplier stored none."
                            + " CONFIDENTIAL: shown masked, never logged",
                    example = "6789",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String payeeTinLast4) {

        /** Not reportable. */
        public static final InformationReturn NONE = new InformationReturn(false, null, null, null, false, null);

        @Override
        public String toString() {
            // payeeTinLast4 is CONFIDENTIAL (ADR-0072): never printed.
            return "InformationReturn[reportable=" + reportable + ", form=" + form + ", box=" + box
                    + ", payeeTaxRegistrationScheme=" + payeeTaxRegistrationScheme + ", payeeTinOnFile="
                    + payeeTinOnFile + "]";
        }
    }

    @Override
    public String toString() {
        // The hold reason is CONFIDENTIAL (ADR-0072): never printed.
        return "VendorApSettingsResponse[defaultDebitClass=" + defaultDebitClass + ", onHold="
                + (apHold != null && apHold.onHold()) + ", informationReturn=" + informationReturn + "]";
    }
}
