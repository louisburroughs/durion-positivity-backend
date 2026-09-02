package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.enums.LaborTimeType;
import com.positivity.catalog.internal.spi.model.VehicleKey;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the one applicable labor time for (service operation, vehicle) (#1569 Phase 1,
 * sourcing plan §3.4): stored standards exact-first with widening vehicle match and
 * policy-driven source precedence, then QUERY_ONLY live sources under a bounded cache, then the
 * service's scalar default hours, then a typed miss.
 */
public interface LaborTimeResolutionService {

    /**
     * @param serviceId the catalog service whose operation is being quoted
     * @param vehicle the vehicle on the lift; {@link VehicleKey#any()} when unknown
     * @param preferredTimeType which time class the workorder wants (warranty vs retail);
     *     null = retail-first default ordering
     */
    @NonNull
    LaborTimeResolution resolve(
            @NonNull UUID serviceId, @NonNull VehicleKey vehicle, @Nullable LaborTimeType preferredTimeType);
}
