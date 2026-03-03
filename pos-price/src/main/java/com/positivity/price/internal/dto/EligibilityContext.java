package com.positivity.price.internal.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** API request payload for promotion eligibility evaluation context. Issue: #96 */
public class EligibilityContext {

    @Nullable
    private UUID accountId;

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
