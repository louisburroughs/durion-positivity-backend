package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.LocationTechnicianRosterEntryResponse;
import com.positivity.shopmanager.internal.dto.MechanicRosterEntryResponse;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MechanicRosterQueryService {

    @NonNull
    Page<MechanicRosterEntryResponse> listMechanics(
            @Nullable MechanicStatus status, @Nullable String skillCode, @NonNull Pageable pageable);

    /**
     * The technicians assigned to a location on {@code date}, each carrying the PLACEHOLDER
     * shift window derived from the location's operating hours for that date (issue #2060; see
     * {@link LocationHoursShiftWindowService}).
     *
     * @param date the roster date; {@code null} means today in the facility's own timezone
     */
    @NonNull
    Page<LocationTechnicianRosterEntryResponse> listLocationTechnicians(
            @NonNull UUID locationId,
            @Nullable MechanicStatus status,
            @Nullable String skillCode,
            @Nullable LocalDate date,
            @NonNull Pageable pageable);
}
