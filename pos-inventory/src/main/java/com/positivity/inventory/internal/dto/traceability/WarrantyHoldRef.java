package com.positivity.inventory.internal.dto.traceability;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A warranty part-return hold linked to a serial number (odoo-parity E5, issue #1051): the
 * downstream warranty-hold arm of serial traceability, materialized from {@code warranty.events.v1}
 * and joined by serial number.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A warranty part-return hold linked to the serial")
public class WarrantyHoldRef {

    @Schema(description = "Identifier of the warranty part-return hold", requiredMode = REQUIRED)
    private UUID partReturnId;

    @Schema(description = "Warranty claim the part return belongs to", requiredMode = NOT_REQUIRED)
    private UUID claimId;

    @Schema(description = "Hold status", example = "REQUESTED", requiredMode = REQUIRED)
    private String status;

    @Schema(description = "Disposition of the returned part, when known", requiredMode = NOT_REQUIRED)
    private String disposition;

    @Schema(description = "When the part return was requested", requiredMode = NOT_REQUIRED)
    private Instant requestedAt;

    @Schema(description = "When the part was shipped back to the provider", requiredMode = NOT_REQUIRED)
    private Instant shippedAt;
}
