package com.positivity.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "Generated invoice identifier.", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID invoiceId;

    @Schema(description = "Invoice status.", example = "DRAFT")
    private String status;

    @Schema(description = "Source workorder identifier.", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID workorderId;

    @Schema(description = "Source estimate identifier.", example = "550e8400-e29b-41d4-a716-446655440002")
    private UUID estimateId;

    @Schema(description = "Approval identifier used for generation.", example = "550e8400-e29b-41d4-a716-446655440003")
    private UUID approvalId;

    @Schema(description = "Subtotal amount before tax.", example = "120.00")
    private BigDecimal subtotal;

    @Schema(description = "Tax amount.", example = "9.60")
    private BigDecimal taxAmount;

    @Schema(description = "Total amount after tax.", example = "129.60")
    private BigDecimal totalAmount;

    @Schema(description = "Creation timestamp (UTC).", example = "2026-02-27T12:00:00Z")
    private Instant createdAt;
}
