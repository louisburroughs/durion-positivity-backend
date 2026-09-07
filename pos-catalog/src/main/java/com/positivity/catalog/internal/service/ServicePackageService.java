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

    /**
     * Creates or updates the package with this code, returning it as it now stands.
     *
     * <p>The bulk-ingest path addresses a package by the code its fixture file names, because ids
     * are minted by whichever environment loads the pack: re-running one has to converge on the
     * same packages instead of failing on the code-uniqueness rule
     * (docs/DATA_SEED_STRATEGY.md §5.3). Members are not touched here — a package's membership is
     * its own file and its own call, so re-loading the package pack cannot silently empty one.
     */
    @NonNull
    ServicePackageResponseDto upsert(@NonNull ServicePackageRequestDto request);

    /**
     * Creates or updates one membership, naming both sides by their codes.
     *
     * <p>Same reason as {@link #upsert}: a fixture file names an operation by its operation code
     * and a package by its package code, and neither id exists until the pack that creates it has
     * run. An existing membership has its sequence, quantity and required flag replaced rather
     * than being refused as a duplicate.
     */
    @NonNull
    ServicePackageResponseDto upsertMember(
            @NonNull String packageCode,
            @NonNull String operationCode,
            @NonNull ServicePackageMemberRequestDto request);

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
