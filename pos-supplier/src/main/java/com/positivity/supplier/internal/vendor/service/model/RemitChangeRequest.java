package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * Asks for a vendor's remit-to to change (#2516). Nothing changes, and nothing is published, until
 * someone other than the requester approves it.
 *
 * @param remitTo the proposed remit-to address
 * @param reason why, at least 10 characters
 */
@Schema(description = "Asks for a vendor's remit-to to change; a second person must approve it.")
public record RemitChangeRequest(
        @Schema(description = "Proposed remit-to address.") @NonNull
        RemitToDto remitTo,

        @Schema(
                description = "Why, at least 10 characters.",
                example = "Vendor letter of 2026-10-01: new lockbox address.")
        @NonNull
        String reason) {

    public RemitChangeRequest {
        if (Objects.isNull(remitTo)) {
            throw VendorFields.invalid("remitTo is required");
        }
        reason = VendorFields.note(reason, "reason");
    }
}
