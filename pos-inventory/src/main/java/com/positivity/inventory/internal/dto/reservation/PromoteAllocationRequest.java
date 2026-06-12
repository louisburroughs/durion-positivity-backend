package com.positivity.inventory.internal.dto.reservation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PromoteAllocationRequest {

    /**
     * Storage location where the hardened allocation pins physical stock.
     * Required: a HARD allocation must be location-attributable so the
     * ALLOCATION_CREATED ledger event can be recorded against it (CAP-218 #656).
     */
    @NotNull
    @Schema(
            description = "Storage location identifier where the hardened allocation pins stock",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID storageLocationId;

    @Schema(description = "Reason the allocation is being hardened")
    private String hardenedReason;
}
