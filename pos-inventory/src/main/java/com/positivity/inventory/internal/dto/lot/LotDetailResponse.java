package com.positivity.inventory.internal.dto.lot;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "An inventory lot with its per-location on-hand balances (odoo-parity E1)")
public class LotDetailResponse {

    @Schema(description = "The lot master record", requiredMode = REQUIRED)
    private LotResponse lot;

    @ArraySchema(
            schema = @Schema(implementation = LotLocationOnHand.class),
            arraySchema =
                    @Schema(
                            description = "Per-location on-hand of this lot, from the per-lot stock summary rows;"
                                    + " locations with fully consumed balances may report zero"))
    private List<LotLocationOnHand> locations;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "On-hand quantity of a lot at one location")
    public static class LotLocationOnHand {

        @Schema(
                description = "Location holding the lot; null for postings recorded without a location",
                example = "01980003-0000-7000-8000-000000000004",
                requiredMode = NOT_REQUIRED)
        private UUID locationId;

        @Schema(description = "On-hand quantity of the lot at the location", example = "12", requiredMode = REQUIRED)
        private BigDecimal onHand;

        @Schema(
                description = "Quantity of the lot in transit toward the location",
                example = "0",
                requiredMode = REQUIRED)
        private BigDecimal inTransitQty;
    }
}
