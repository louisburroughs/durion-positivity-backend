package com.positivity.inventory.internal.dto.sourcing;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.SourcingScopeType;
import com.positivity.inventory.internal.enums.SourcingStrategy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "One sourcing strategy configuration row (odoo-parity H1)")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourcingStrategyConfigResponse {

    @Schema(
            description = "Unique identifier of the configuration row",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID configId;

    @Schema(description = "Configuration scope kind", example = "SITE", requiredMode = REQUIRED)
    @NotNull
    private SourcingScopeType scopeType;

    @Schema(
            description = "Scope key: category string, site UUID as text, or null for DEFAULT",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = NOT_REQUIRED)
    private String scopeValue;

    @Schema(description = "Sourcing strategy applied at this scope", example = "PROXIMITY", requiredMode = REQUIRED)
    @NotNull
    private SourcingStrategy strategy;

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
