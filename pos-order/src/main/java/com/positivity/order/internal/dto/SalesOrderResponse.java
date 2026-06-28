package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response representing a sales order and its lines")
public class SalesOrderResponse {

    @Schema(
            description = "Unique identifier of the order",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private String orderId;

    @Schema(
            description = "Identifier of the customer associated with the order",
            example = "01960003-0000-7000-8000-000000000070",
            requiredMode = NOT_REQUIRED)
    private String customerId;

    @Schema(
            description = "Identifier of the vehicle associated with the order",
            example = "01960003-0000-7000-8000-000000000080",
            requiredMode = NOT_REQUIRED)
    private String vehicleId;

    @Schema(
            description = "Identifier of the clerk who owns the order",
            example = "01960003-0000-7000-8000-000000000050",
            requiredMode = NOT_REQUIRED)
    private String clerkId;

    @Schema(
            description = "Identifier of the terminal where the order was created",
            example = "01960003-0000-7000-8000-000000000060",
            requiredMode = NOT_REQUIRED)
    private String terminalId;

    @Schema(description = "Current status of the order", example = "OPEN", requiredMode = REQUIRED)
    @NotNull
    private String status;

    @Schema(
            description = "Subtotal of all order lines before taxes and fees",
            example = "59.97",
            requiredMode = NOT_REQUIRED)
    private BigDecimal subtotal;

    @Schema(
            description = "Timestamp when the order was created (ISO 8601)",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the order was last updated (ISO 8601)",
            example = "2026-01-15T09:45:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;

    @Schema(
            description = "Identifier of the user who created the order",
            example = "01960003-0000-7000-8000-000000000050",
            requiredMode = NOT_REQUIRED)
    private String createdBy;

    @Schema(
            description = "Identifier of the user who last updated the order",
            example = "01960003-0000-7000-8000-000000000051",
            requiredMode = NOT_REQUIRED)
    private String updatedBy;

    @Schema(description = "Lines belonging to the order", requiredMode = NOT_REQUIRED)
    @Valid
    @Builder.Default
    private List<SalesOrderLineResponse> lines = List.of();
}
