package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.ShortageResolutionOption;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request to execute a chosen shortage resolution option atomically (odoo-parity G2, issue #1049).
 * {@code idempotencyKey} makes the resolve retry-safe — a replay with the same key returns the
 * original result without re-executing the option.
 */
@Schema(description = "Request to resolve an inventory shortage by executing a chosen option")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortageResolveRequest {

    @Schema(
            description = "Retry-safe idempotency key; a replay with the same key returns the original result",
            example = "shortage-resolve-01960003-0001",
            requiredMode = REQUIRED)
    @NotBlank
    private String idempotencyKey;

    @Schema(
            description = "Identifier of the allocation to resolve",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID allocationId;

    @Schema(description = "The resolution option to execute", example = "BACKORDER", requiredMode = REQUIRED)
    @NotNull
    private ShortageResolutionOption optionType;

    @Schema(
            description = "SKU / stock-item identifier that is short",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotBlank
    private String sku;

    @Schema(description = "Quantity that is short and to be resolved", example = "3", requiredMode = REQUIRED)
    @Positive
    private BigDecimal shortQuantity;

    @Schema(
            description = "Site the demand is short at (required for BACKORDER / TRANSFER_IN)",
            example = "01960004-0001-7000-8000-00000000000c",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID locationId;

    @Schema(
            description = "Workorder line whose demand was short (required for BACKORDER / SUBSTITUTE / CANCEL_LINE)",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID workorderLineId;

    @Schema(
            description = "Substitute SKU to reserve; required when optionType is SUBSTITUTE",
            example = "01960003-0000-7000-8000-000000000099",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String substituteSku;

    @Schema(
            description = "Source site to pull surplus from; required when optionType is TRANSFER_IN",
            example = "01960004-0001-7000-8000-00000000000a",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID sourceLocationId;

    @Schema(
            description = "Optional free-text notes explaining the resolution",
            example = "Customer approved substitute item",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String notes;
}
