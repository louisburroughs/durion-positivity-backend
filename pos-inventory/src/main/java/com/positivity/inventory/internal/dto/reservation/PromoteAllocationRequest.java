package com.positivity.inventory.internal.dto.reservation;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to promote a soft allocation to a hardened, location-pinned allocation")
public class PromoteAllocationRequest {

    /**
     * Storage location where the hardened allocation pins physical stock.
     * Required: a HARD allocation must be location-attributable so the
     * ALLOCATION_CREATED ledger event can be recorded against it (CAP-218 #656).
     */
    @NotNull
    @Schema(
            description = "Storage location identifier where the hardened allocation pins stock",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID storageLocationId;

    @Schema(
            description = "Reason the allocation is being hardened",
            example = "Reserved for scheduled workorder pick",
            requiredMode = NOT_REQUIRED)
    private String hardenedReason;
}
