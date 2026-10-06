package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

/**
 * Deactivates or reactivates a vendor (#2516).
 *
 * @param reason why, at least 10 characters; kept on the vendor and published with the fact
 */
@Schema(description = "Deactivates or reactivates a vendor.")
public record VendorStatusChangeRequest(
        @Schema(description = "Why, at least 10 characters.", example = "Vendor merged into Michelin; use MICHELIN.")
        @NonNull
        String reason) {

    public VendorStatusChangeRequest {
        reason = VendorFields.note(reason, "reason");
    }
}
