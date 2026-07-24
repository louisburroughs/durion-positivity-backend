package com.positivity.inventory.internal.dto.costing;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.CostingScopeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "One costing method configuration row (odoo-parity J1)")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CostingMethodConfigResponse {

    @Schema(
            description = "Unique identifier of the configuration row",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID configId;

    @Schema(description = "Configuration scope kind", example = "DEFAULT", requiredMode = REQUIRED)
    @NotNull
    private CostingScopeType scopeType;

    @Schema(
            description = "Scope key: stock item id, category string, or null for DEFAULT",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = NOT_REQUIRED)
    private String scopeValue;

    @Schema(description = "Costing method applied at this scope", example = "AVERAGE", requiredMode = REQUIRED)
    @NotNull
    private CostingMethod method;

    @Schema(description = "Whether this row participates in resolution", example = "true", requiredMode = REQUIRED)
    private boolean active;

    @Schema(
            description = "Timestamp when the row was created",
            example = "2026-07-01T00:00:00Z",
            requiredMode = REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the row was last updated",
            example = "2026-07-01T00:00:00Z",
            requiredMode = REQUIRED)
    private Instant updatedAt;
}
