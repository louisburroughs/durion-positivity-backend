package com.positivity.inventory.internal.dto.receiving;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A single line within a receiving session, with expected and received quantities")
public class ReceivingLineResponse {
    @Schema(
            description = "Identifier of the receiving line",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID lineId;

    @Schema(
            description = "Identifier of the product on the receiving line",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private String productId;

    @Schema(description = "Quantity expected to be received on the line", example = "10", requiredMode = NOT_REQUIRED)
    private BigDecimal expectedQuantity;

    @Schema(description = "Quantity received so far on the line", example = "8", requiredMode = NOT_REQUIRED)
    private BigDecimal receivedQuantity;

    @Schema(
            description = "Status of the receiving line, such as PENDING or RECEIVED",
            example = "RECEIVED",
            requiredMode = REQUIRED)
    @NotNull
    private String status;

    @Schema(
            description = "Identifier of the workorder the line is associated with, when applicable",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private String workorderId;

    @Schema(
            description = "Identifier of the workorder line the receiving line fulfils, when applicable",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    private String workorderLineId;
}
