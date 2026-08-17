package com.positivity.image.internal.repository;

import com.positivity.image.internal.entity.ImageEntity;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Stored image records (CAP-324 #1257). */
@Repository
public interface ImageRepository extends JpaRepository<ImageEntity, Long> {

    /**
     * The image already held for this origin and content, if there is one (CAP-324 #1257).
     *
     * <p>Both halves matter. Matching on the source alone would return a stale record after a
     * manufacturer replaced the artwork behind a URL it kept; matching on content alone would return
     * an unrelated record that happens to be the same picture, and hand back its filename and
     * context. Together they mean "we have already ingested exactly this, from exactly here".
     */
    @NonNull
    Optional<ImageEntity> findFirstBySourceUriAndContentHash(@NonNull String sourceUri, @NonNull String contentHash);

    @NonNull
    Optional<ImageEntity> findByFilename(@NonNull String filename);
}
