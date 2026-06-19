package com.positivity.inventory.internal.dto.receiving;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Result of cross-docking received stock against a workorder line")
public class CrossDockResponse {
    @Schema(
            description = "Identifier of the receiving line that was cross-docked",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID lineId;

    @Schema(
            description = "Identifier of the workorder the stock was cross-docked to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private String workorderId;

    @Schema(
            description = "Identifier of the workorder line the stock fulfilled",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @NotNull
    private String workorderLineId;

    @Schema(
            description = "Quantity of stock that was cross-docked",
            example = "12",
            requiredMode = REQUIRED)
    @NotNull
    private BigDecimal crossDockedQuantity;

    @Schema(
            description = "Identifiers of the inventory ledger entries created by the cross-dock",
            example = "[\"01960003-0000-7000-8000-000000000004\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> ledgerEntryIds;

    @Schema(
            description = "Status of the receiving session after the cross-dock",
            example = "COMPLETED",
            requiredMode = REQUIRED)
    @NotNull
    private String sessionStatus;

    @Schema(
            description = "Status of the receiving line after the cross-dock",
            example = "RECEIVED",
            requiredMode = REQUIRED)
    @NotNull
    private String lineStatus;
}
