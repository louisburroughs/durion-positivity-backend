package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.ShopResponse;
import com.positivity.shopmanager.internal.dto.ShopUpsertRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Creates or replaces the scheduling configuration a location needs to be schedulable. */
public interface ShopConfigurationService {

    /**
     * Creates the shop for {@code locationId}, or replaces its configuration when one already
     * exists. Idempotent by the location id, so a reseed converges rather than duplicating.
     */
    @NonNull
    ShopResponse upsert(@NonNull UUID locationId, @NonNull ShopUpsertRequest request);
}
