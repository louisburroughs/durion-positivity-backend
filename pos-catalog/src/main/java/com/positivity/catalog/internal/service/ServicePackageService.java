package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.ServicePackageMemberRequestDto;
import com.positivity.catalog.internal.dto.ServicePackageRequestDto;
import com.positivity.catalog.internal.dto.ServicePackageResponseDto;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Authoring and reading of service packages and fleet requirement sets (#1575 Tier 0, T0-4). */
public interface ServicePackageService {

    @NonNull
    ServicePackageResponseDto create(@NonNull ServicePackageRequestDto request);

    @NonNull
    ServicePackageResponseDto get(@NonNull UUID packageId);

    /**
     * @param locationId packages this location may sell — its own plus every platform package;
     *     null lists platform packages only
     * @param fleetPartyId narrow to one fleet account's requirement set; null does not narrow
     * @param includeFleetPackages when false (the default), fleet requirement sets are excluded
     *     from a general listing, because they belong to one account and are not on offer
     */
    @NonNull
    List<ServicePackageResponseDto> list(
            @Nullable UUID locationId, @Nullable UUID fleetPartyId, boolean includeFleetPackages);

    @NonNull
    ServicePackageResponseDto addMember(@NonNull UUID packageId, @NonNull ServicePackageMemberRequestDto request);

    @NonNull
    ServicePackageResponseDto removeMember(@NonNull UUID packageId, @NonNull UUID memberId);
}
