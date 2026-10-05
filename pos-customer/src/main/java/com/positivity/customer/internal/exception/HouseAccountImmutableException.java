package com.positivity.customer.internal.exception;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * A write targeted a system house account (the tenant's CASH walk-in account), which no request
 * may change, merge, delete or attach data to. Maps to {@code 409 HOUSE_ACCOUNT_IMMUTABLE}.
 */
public class HouseAccountImmutableException extends RuntimeException {

    private final UUID partyId;

    public HouseAccountImmutableException(@NonNull UUID partyId) {
        super("Party " + partyId + " is a system house account and cannot be changed");
        this.partyId = partyId;
    }

    public @NonNull UUID getPartyId() {
        return partyId;
    }
}
