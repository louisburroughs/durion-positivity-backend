package com.positivity.shared.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for invoice generation from workorder.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response payload returned after invoice generation.")
public class InvoiceGenerationResponse {

    @Schema(
            description = "Generated invoice identifier.",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    @NotNull
    private UUID invoiceId;

    @Schema(description = "Invoice status.", example = "DRAFT", requiredMode = REQUIRED)
    @NotNull
    private String status;

    @Schema(
            description = "Source workorder identifier.",
            example = "550e8400-e29b-41d4-a716-446655440001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(
            description = "Source estimate identifier.",
            example = "550e8400-e29b-41d4-a716-446655440002",
            requiredMode = NOT_REQUIRED)
    private UUID estimateId;

    @Schema(
            description = "Approval identifier used for generation.",
            example = "550e8400-e29b-41d4-a716-446655440003",
            requiredMode = NOT_REQUIRED)
    private UUID approvalId;

    @Schema(description = "Subtotal amount before tax.", example = "120.00", requiredMode = REQUIRED)
    @NotNull
    private BigDecimal subtotal;

    @Schema(description = "Tax amount.", example = "9.60", requiredMode = REQUIRED)
    @NotNull
    private BigDecimal taxAmount;

    @Schema(description = "Total amount after tax.", example = "129.60", requiredMode = REQUIRED)
    @NotNull
    private BigDecimal totalAmount;

    @Schema(description = "Creation timestamp (UTC).", example = "2026-02-27T12:00:00Z", requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;
}
