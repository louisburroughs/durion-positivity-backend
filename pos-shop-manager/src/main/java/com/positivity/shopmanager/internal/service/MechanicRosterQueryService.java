package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.LocationTechnicianRosterEntryResponse;
import com.positivity.shopmanager.internal.dto.MechanicRosterEntryResponse;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MechanicRosterQueryService {

    @NonNull
    Page<MechanicRosterEntryResponse> listMechanics(
            @Nullable MechanicStatus status, @Nullable String skillCode, @NonNull Pageable pageable);

    @NonNull
    Page<LocationTechnicianRosterEntryResponse> listLocationTechnicians(
            @NonNull UUID locationId,
            @Nullable MechanicStatus status,
            @Nullable String skillCode,
            @NonNull Pageable pageable);
}
