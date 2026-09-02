package com.positivity.catalog.internal.spi;

import com.positivity.catalog.internal.spi.model.LaborTimeProviderDescriptor;
import com.positivity.catalog.internal.spi.model.ProviderFeedChunk;
import com.positivity.catalog.internal.spi.model.ProviderFeedRevision;
import com.positivity.catalog.internal.spi.model.ProviderLaborTime;
import com.positivity.catalog.internal.spi.model.ProviderOperation;
import com.positivity.catalog.internal.spi.model.VehicleKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The Durion-normalized labor-time provider contract (#1569 Phase 1, sourcing plan §3.3).
 * Vendors adapt to this; nothing in the platform adapts to a vendor.
 *
 * <p>Live-lookup methods throw {@link ProviderCallException} for transport-level failure —
 * callers translate that into typed degradation ({@code SOURCE_UNAVAILABLE}), never let it
 * escape to a client. Feed methods exist only for STORE-licensed sources; a QUERY_ONLY
 * source throws {@link UnsupportedOperationException} from them and is used live-only.
 */
public interface LaborTimeProviderPort {

    /** Identity + capability declaration; drives precedence defaults and licensing mode. */
    @NonNull
    LaborTimeProviderDescriptor descriptor();

    /** Live lookup: operations applicable to a vehicle, optionally filtered by search text. */
    @NonNull
    List<ProviderOperation> findOperations(@NonNull VehicleKey vehicle, @Nullable String search);

    /** Live lookup: the most specific published time for (vehicle, vendor operation). */
    @NonNull
    Optional<ProviderLaborTime> getLaborTime(@NonNull VehicleKey vehicle, @NonNull String providerOperationCode);

    /**
     * Batch mode for STORE-licensed sources: the manifest of the provider's current feed
     * revision. {@code sinceRevision} lets the provider answer "nothing newer", though a
     * provider may simply return the current manifest and leave skipping to the importer.
     */
    @NonNull
    ProviderFeedRevision openFeedRevision(@Nullable String sinceRevision);

    /** Batch mode: one chunk of the revision named by the manifest. */
    @NonNull
    ProviderFeedChunk fetchFeedChunk(@NonNull UUID importManifestId, int chunkSequence);
}
