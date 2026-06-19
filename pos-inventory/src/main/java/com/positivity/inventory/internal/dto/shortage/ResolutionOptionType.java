package com.positivity.inventory.internal.dto.shortage;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Type of resolution proposed for an inventory shortage")
public enum ResolutionOptionType {
    @Schema(description = "Resolve the shortage with a substitute part")
    SUBSTITUTE,

    @Schema(description = "Resolve the shortage via an external purchase order")
    EXTERNAL_PURCHASE,

    @Schema(description = "Resolve the shortage by placing the demand on backorder")
    BACKORDER
}
