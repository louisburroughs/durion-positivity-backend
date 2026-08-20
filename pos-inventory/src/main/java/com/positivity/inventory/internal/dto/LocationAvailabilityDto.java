package com.positivity.inventory.internal.dto;

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

/**
 * Per-location availability projection for inventory availability queries.
 *
 * Issue: CAP-170 (#48)
 */
@Schema(description = "Per-location availability projection for inventory availability queries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationAvailabilityDto {

    @Schema(
            description = "Identifier of the location",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(description = "Human-readable name of the location", example = "Main Warehouse", requiredMode = REQUIRED)
    @NotNull
    private String locationName;

    @Schema(description = "On-hand quantity at the location", example = "120", requiredMode = REQUIRED)
    private BigDecimal onHandQuantity;

    @Schema(
            description = "Available-to-promise quantity at the location. Null for as-of (historical) requests: "
                    + "historical allocation state is not reliably reconstructable from ATP-neutral ledger events, "
                    + "so as-of responses carry on-hand only.",
            example = "90",
            requiredMode = NOT_REQUIRED)
    private BigDecimal availableToPromiseQuantity;

    @Schema(
            description =
                    "Open expected supply at the site: approved purchase-order open line quantity plus un-received "
                            + "ASN remainder. Bounded by the 'horizon' query parameter when present (documents without "
                            + "an expected date are excluded from horizon-bounded results). Omitted for as-of requests.",
            example = "25",
            requiredMode = NOT_REQUIRED)
    private BigDecimal incomingQty;

    @Schema(
            description =
                    "Open expected demand not yet decremented from on-hand: unallocated reservation remainders plus "
                            + "released-not-picked pick-task remainders. Reservation demand carries no site and is "
                            + "included in every site row. Omitted for as-of requests.",
            example = "10",
            requiredMode = NOT_REQUIRED)
    private BigDecimal outgoingQty;

    @Schema(
            description = "Projected availability: onHandQuantity + incomingQty - outgoingQty "
                    + "(Odoo virtual_available). Omitted for as-of requests.",
            example = "135",
            requiredMode = NOT_REQUIRED)
    private BigDecimal projectedAvailable;
}
