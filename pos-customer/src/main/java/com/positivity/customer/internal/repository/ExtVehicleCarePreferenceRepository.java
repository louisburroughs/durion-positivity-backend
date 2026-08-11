package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.ExtVehicleCarePreference;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExtVehicleCarePreferenceRepository extends JpaRepository<ExtVehicleCarePreference, UUID> {

    /**
     * Smallest per-vehicle override on record, or null when no vehicle carries one (#1175). The
     * reminder job queries candidates at the loosest interval in effect (the smaller of this and
     * the module default) and then filters per candidate in Java, so a tight per-vehicle interval
     * can never be missed by the SQL cutoff.
     */
    @Query("select min(p.serviceIntervalMonths) from ExtVehicleCarePreference p"
            + " where p.serviceIntervalMonths is not null")
    @Nullable
    Integer findMinServiceIntervalMonths();
}
