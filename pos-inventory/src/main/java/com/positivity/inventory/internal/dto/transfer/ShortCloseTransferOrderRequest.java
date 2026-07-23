package com.positivity.inventory.internal.dto.transfer;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.TransferShortCloseDisposition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for short-closing a transfer order (odoo-parity C3, issue #1039; spec C4).
 *
 * <p>The disposition applies to every line's undelivered remainder
 * ({@code dispatchedQty - receivedQty}) — one disposition per order, no per-line mix (v1
 * simplification). Reason and notes are mandatory: short-close accepts a loss or reverses a
 * movement, so the audit trail must say why.
 */
@Schema(
        description = "Request to short-close a transfer order, resolving the undelivered remainder"
                + " with a whole-order disposition")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShortCloseTransferOrderRequest {

    @Schema(
            description = "Disposition of the undelivered remainder: LOST_IN_TRANSIT writes it off"
                    + " (SCRAP_OUT, reason LOST); RETURNED_TO_SOURCE restores it to source on-hand",
            requiredMode = REQUIRED)
    @NotNull(message = "Disposition is required")
    private TransferShortCloseDisposition disposition;

    @Schema(description = "Reason for the short-close (mandatory)", requiredMode = REQUIRED)
    @NotBlank(message = "Reason is required")
    @Size(max = 100, message = "Reason must not exceed 100 characters")
    private String reason;

    @Schema(description = "Free-text notes explaining the short-close (mandatory)", requiredMode = REQUIRED)
    @NotBlank(message = "Notes are required")
    @Size(max = 2000, message = "Notes must not exceed 2000 characters")
    private String notes;
}
