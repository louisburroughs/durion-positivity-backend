package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request DTO for correcting part quantity (administrative correction).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload for administratively correcting authorized part quantity")
public class CorrectPartQuantityRequest {

    /**
     * ID of the workorder part.
     */
    @NotNull(message = "Workorder part ID is required")
    @Schema(
            description = "Workorder part identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID workorderPartId;

    /**
     * New authorized quantity (must be positive).
     */
    @NotNull(message = "New quantity is required")
    @DecimalMin(value = "0.0001", message = "newQuantity must be greater than 0")
    @Schema(description = "Corrected authorized quantity", example = "2", requiredMode = REQUIRED)
    private BigDecimal newQuantity;

    /**
     * Reason for correction (required for audit).
     */
    @NotBlank(message = "Reason is required")
    @Schema(
            description = "Audit reason for correction",
            example = "Inventory recount adjusted authorized quantity",
            requiredMode = REQUIRED)
    private String reason;

    /**
     * Optional additional notes.
     */
    @Nullable
    @Schema(
            description = "Optional correction notes",
            example = "Adjusted after parts counter verification",
            requiredMode = NOT_REQUIRED)
    private String notes;

    /**
     * Unit {@code newQuantity} is expressed in. {@code null} leaves the part's existing unit
     * unchanged (a correction that only fixes the number, not the unit); the product's base unit
     * still applies when the part carries no unit of its own.
     */
    @Nullable
    @Schema(
            description = "Unit newQuantity is expressed in. Omit to leave the part's existing unit unchanged. "
                    + "Converted to base and validated against the product's catalog divisibility.",
            example = "QT",
            requiredMode = NOT_REQUIRED)
    private String uomCode;
}
