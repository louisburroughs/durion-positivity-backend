package com.positivity.order.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.order.internal.enums.PurchaseOrderVendorStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * One vendor as the purchase-order screens see it (CAP:550, #2680): the ADR-0044 R3 minimum pos-order keeps in its
 * copy of the vendor master, and nothing else. Remit-to, tax registrations, currency and payment terms stay in
 * pos-supplier behind {@code supplier:vendor:read}.
 *
 * @param vendorId the pos-supplier vendor id a purchase order names
 * @param vendorNumber the vendor's number, as pos-supplier assigns it
 * @param displayName the vendor's display name; CONFIDENTIAL under ADR-0072, never logged
 * @param status the vendor's status; only ACTIVE vendors pass the purchase-order vendor guard
 */
@Schema(description = "A vendor of pos-order's vendor copy: the number, name and status a purchase order needs")
public record PurchaseOrderVendorResponse(
        @Schema(
                description = "The pos-supplier vendor id to name on a purchase order",
                example = "01960003-0000-7000-8000-000000000001",
                requiredMode = REQUIRED)
        UUID vendorId,

        @Schema(description = "The vendor number", example = "V-000123", requiredMode = REQUIRED)
        String vendorNumber,

        @Schema(description = "The vendor's display name", example = "Acme Tire Supply", requiredMode = REQUIRED)
        String displayName,

        @Schema(
                description = "ACTIVE vendors take new purchase orders; INACTIVE ones are refused at create,"
                        + " approve and transmit (422 VENDOR_INACTIVE)",
                example = "ACTIVE",
                requiredMode = REQUIRED)
        PurchaseOrderVendorStatus status) {}
