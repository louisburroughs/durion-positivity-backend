package com.positivity.inventory.internal.dto.costing;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.CostingScopeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Upsert of the costing method configured for one scope (odoo-parity J1)")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CostingMethodConfigRequest {

    @Schema(description = "Configuration scope kind", example = "DEFAULT", requiredMode = REQUIRED)
    @NotNull
    private CostingScopeType scopeType;

    @Schema(
            description = "Scope key: the stock item id for SKU, the category string for SKU_CATEGORY,"
                    + " omitted for DEFAULT",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = NOT_REQUIRED)
    private String scopeValue;

    @Schema(description = "Costing method applied at this scope", example = "AVERAGE", requiredMode = REQUIRED)
    @NotNull
    private CostingMethod method;
}
