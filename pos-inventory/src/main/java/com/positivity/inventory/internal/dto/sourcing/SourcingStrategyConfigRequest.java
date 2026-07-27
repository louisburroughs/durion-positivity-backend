package com.positivity.inventory.internal.dto.sourcing;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.SourcingScopeType;
import com.positivity.inventory.internal.enums.SourcingStrategy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Upsert of the sourcing strategy configured for one scope (odoo-parity H1)")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourcingStrategyConfigRequest {

    @Schema(description = "Configuration scope kind", example = "SITE", requiredMode = REQUIRED)
    @NotNull
    private SourcingScopeType scopeType;

    @Schema(
            description = "Scope key: the SKU category string for SKU_CATEGORY, the site UUID as text for SITE,"
                    + " omitted for DEFAULT",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = NOT_REQUIRED)
    private String scopeValue;

    @Schema(description = "Sourcing strategy applied at this scope", example = "PROXIMITY", requiredMode = REQUIRED)
    @NotNull
    private SourcingStrategy strategy;
}
