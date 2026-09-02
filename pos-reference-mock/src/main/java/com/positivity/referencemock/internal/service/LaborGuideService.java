package com.positivity.referencemock.internal.service;

import com.positivity.referencemock.internal.dto.FeedChunkDto;
import com.positivity.referencemock.internal.dto.FeedManifestDto;
import com.positivity.referencemock.internal.dto.ProviderLaborTimeDto;
import com.positivity.referencemock.internal.dto.ProviderOperationDto;
import com.positivity.referencemock.internal.dto.VehicleQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Fixture-backed implementation of the Durion-normalized labor-guide provider contract
 * (plan §10). All answers are deterministic functions of the checked-in fixture file.
 */
public interface LaborGuideService {

    /**
     * Operations applicable to the given vehicle, optionally narrowed by a case-insensitive
     * substring search on the operation name. Absent vehicle fields are wildcards.
     *
     * @param vehicle the (possibly partial) vehicle key; null fields match everything
     * @param search case-insensitive substring on the operation name, or null for no filter
     * @return applicable operations in fixture catalog order
     */
    @NonNull
    List<ProviderOperationDto> findOperations(@NonNull VehicleQuery vehicle, String search);

    /**
     * The most specific labor time for (vehicle, provider operation). A fixture row matches when
     * every non-null row field equals the corresponding request field (case-insensitive); rows
     * with more non-wildcard fields beat rows with fewer.
     *
     * @param providerOperationCode the vendor operation code to resolve
     * @param vehicle the (possibly partial) vehicle key
     * @return the winning time, or empty when no row matches
     */
    Optional<ProviderLaborTimeDto> findLaborTime(@NonNull String providerOperationCode, @NonNull VehicleQuery vehicle);

    /**
     * Manifest of the current fixture revision (id, counts, checksum). Deterministic: calling it
     * twice yields identical values.
     *
     * @return the manifest for the current revision
     */
    @NonNull
    FeedManifestDto manifest();

    /**
     * One 50-line chunk of the feed by 1-based sequence.
     *
     * @param chunkSequence 1-based chunk sequence
     * @param manifestId manifest id the caller is importing; must match the current manifest
     * @return the chunk, or empty for an unknown sequence or a mismatched manifest id
     */
    Optional<FeedChunkDto> chunk(int chunkSequence, @NonNull UUID manifestId);
}
