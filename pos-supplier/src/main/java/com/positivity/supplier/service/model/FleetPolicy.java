package com.positivity.supplier.service.model;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What a fleet program will and will not pay for (CAP-323 #1229).
 *
 * <p>Advisory only. A policy read here narrows what a service advisor offers; it never substitutes
 * for asking the vendor to authorize. The vendor decides, and a local pre-filter that guessed wrong
 * would either quote work the fleet refuses or refuse work the fleet would have paid for — both
 * invisible, because no request was ever made.
 *
 * @param policyId       the vendor's identifier for the policy
 * @param name           as stated
 * @param useBlacklist   whether the vendor says the exclusion list applies
 * @param useWhitelist   whether the vendor says the inclusion list applies
 * @param blacklisted    product or operation references the fleet excludes, as stated
 * @param whitelisted    product or operation references the fleet permits, as stated
 */
public record FleetPolicy(
        @Nullable String policyId,
        @Nullable String name,
        boolean useBlacklist,
        boolean useWhitelist,
        @NonNull List<String> blacklisted,
        @NonNull List<String> whitelisted) {

    public FleetPolicy {
        Objects.requireNonNull(blacklisted, "blacklisted must not be null");
        Objects.requireNonNull(whitelisted, "whitelisted must not be null");
        blacklisted = List.copyOf(blacklisted);
        whitelisted = List.copyOf(whitelisted);
    }
}
