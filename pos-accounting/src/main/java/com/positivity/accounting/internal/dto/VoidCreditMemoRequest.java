package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Request to void a POSTED Credit Memo (issue #997 symmetry). The reason is mandatory for the
 * audit trail, mirroring the creation-side {@code reasonCode} requirement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Void a POSTED credit memo, restoring AR and the reversed tax liability")
public class VoidCreditMemoRequest {

    @Schema(
            description = "Reason the memo is being voided (audit trail)",
            example = "Issued against the wrong invoice",
            requiredMode = REQUIRED,
            minLength = 1,
            maxLength = 1000)
    @NotBlank(message = "voidReason is required")
    @Size(max = 1000, message = "voidReason must not exceed 1000 characters")
    private String voidReason;
}
