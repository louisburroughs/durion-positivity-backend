package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

/**
 * Approves a pending remit-to change (#2516).
 *
 * @param verificationNote how the change was checked, at least 10 characters
 */
@Schema(description = "Approves a pending remit-to change.")
public record RemitApprovalRequest(
        @Schema(
                description = "How the change was checked, at least 10 characters.",
                example = "Called the vendor's AR line from the number on file; confirmed the new lockbox.")
        @NonNull
        String verificationNote) {

    public RemitApprovalRequest {
        verificationNote = VendorFields.note(verificationNote, "verificationNote");
    }
}
