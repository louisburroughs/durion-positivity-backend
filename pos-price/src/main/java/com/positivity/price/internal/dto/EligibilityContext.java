package com.positivity.price.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * API request payload for promotion eligibility evaluation context. Issue: #96
 */
@Schema(description = "Optional account/vehicle context used to evaluate promotion eligibility")
public class EligibilityContext {

    @Schema(
            description = "Customer account identifier",
            example = "9fdaf7eb-b58f-4e4f-8c14-035e9368f7e8",
            nullable = true)
    @Nullable
    private UUID accountId;

    @Schema(
            description = "Vehicle identifier used for vehicle-based eligibility rules",
            example = "0b4a9f7c-f5a8-4a5f-b3cb-2b7a3f4ec5a1",
            nullable = true)
    @Nullable
    private UUID vehicleId;

    @Nullable
    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(@Nullable UUID accountId) {
        this.accountId = accountId;
    }

    @Nullable
    public UUID getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(@Nullable UUID vehicleId) {
        this.vehicleId = vehicleId;
    }
}
