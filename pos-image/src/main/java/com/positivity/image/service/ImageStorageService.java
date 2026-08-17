package com.positivity.image.service;

import com.positivity.image.service.model.StoredImage;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Putting image bytes into this service (CAP-324 #1257, ADR-0009).
 *
 * <h2>Why this exists</h2>
 *
 * pos-image could previously only serve images that had arrived by some other route — a file placed
 * on a volume, a URL recorded by hand. Nothing could put one in. MKCAT ingestion fetches
 * manufacturer artwork from a vendor and has to keep it, because the alternative is publishing the
 * vendor's own URL as the render source, which makes every product page depend on a third party's
 * uptime and rate limits and gives this service nothing to cache or optimise.
 *
 * <h2>Storing is idempotent</h2>
 *
 * Storing the same bytes from the same origin twice returns the first reference rather than a second
 * copy. That is not an optimisation: a catalogue refresh republishes unchanged artwork every cycle,
 * so a store that took it at face value would grow forever and give a consumer a new id each time
 * for a picture that never changed.
 */
public interface ImageStorageService {

    /**
     * Stores image bytes, or returns the reference already held for them.
     *
     * @param filename    name to store it under
     * @param contentType MIME type; the caller's claim about the bytes, not a sniff of them
     * @param content     the bytes. Must not be empty — an empty image is a failed download, and
     *                    storing one would satisfy every later "do we have it?" check
     * @param sourceUri   where the bytes came from, for provenance. May be null for direct uploads
     * @param context     tags this image should carry
     * @return the reference, with {@code newlyStored} saying whether this call did the storing
     */
    @NonNull
    StoredImage store(
            @NonNull String filename,
            @NonNull String contentType,
            byte[] content,
            @Nullable String sourceUri,
            @Nullable List<String> context);
}
