package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * A vendor as accounting's copy of the pos-supplier vendor master holds it (Issue #816; CAP:550 S24, #2517). Used by
 * the frontend vendor typeahead to resolve a vendor name to its pos-supplier vendorId (and back, for deep-linked ids),
 * and by the AP screens to see whether the vendor's payment details changed since its bills were approved.
 */
@Value
@Builder
@Schema(
        description =
                "A vendor from accounting's copy of the pos-supplier vendor master: the pos-supplier vendorId, its"
                        + " name, number and status, its current remit-to version and whether payment details changed")
public class VendorResponse {

    /** The pos-supplier vendor id: the one key of bills, AP payments and purchase orders. */
    @Schema(
            description = "The pos-supplier vendor id, the one key bills, AP payments and purchase orders name",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    UUID vendorId;

    /** Vendor display name. */
    @Schema(description = "Vendor display name", example = "Acme Auto Parts", requiredMode = REQUIRED)
    String name;

    /** The tenant-unique vendor number people quote. */
    @Schema(description = "The tenant-unique vendor number people quote", example = "V-000123", requiredMode = REQUIRED)
    String vendorNumber;

    /** Vendor status: an INACTIVE vendor takes no new bill, payment or purchase order. */
    @Schema(
            description =
                    "ACTIVE, or INACTIVE: an inactive vendor takes no new bill, payment or purchase order, and its"
                            + " open bills are not paid while it is inactive",
            example = "ACTIVE",
            allowableValues = {"ACTIVE", "INACTIVE"},
            requiredMode = REQUIRED)
    String status;

    /** The vendor's current remit-to version: 0 with no remit-to, then +1 per approved change. */
    @Schema(
            description = "The vendor's current remit-to version: 0 with no remit-to, 1 for one given at creation, then"
                    + " +1 per approved change; a bill approved at another version is not paid until it is confirmed",
            example = "2",
            requiredMode = REQUIRED)
    int remitToVersion;

    /**
     * Whether at least one approved, open bill of the vendor was approved at another remit-to version than the current
     * one, and nobody confirmed the current version: payment of those bills is refused until someone confirms it.
     */
    @Schema(
            description = "True when an approved, open bill of the vendor was approved at another remit-to version than"
                    + " the current one and nobody has confirmed the current version: paying it answers 409"
                    + " VENDOR_PAYMENT_DETAILS_CHANGED until a holder of accounting:ap:approve other than the payer"
                    + " confirms it",
            example = "false",
            requiredMode = REQUIRED)
    boolean paymentDetailsChanged;

    /** The vendor's accounting-side settings; only on the single-vendor read. */
    @Schema(
            description = "The vendor's accounting-side settings (AP defaults and remit-to confirmation); returned by"
                    + " getVendorById and the ap-settings PUT, null in a search",
            requiredMode = NOT_REQUIRED)
    VendorApSettingsResponse apSettings;
}
