package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

/**
 * Rejects a pending remit-to change (#2516). The vendor is unchanged.
 *
 * @param note why, at least 10 characters
 */
@Schema(description = "Rejects a pending remit-to change; the vendor is unchanged.")
public record RemitRejectionRequest(
        @Schema(
                description = "Why, at least 10 characters.",
                example = "Vendor's AR line knows nothing of this change.")
        @NonNull
        String note) {

    public RemitRejectionRequest {
        note = VendorFields.note(note, "note");
    }
}
