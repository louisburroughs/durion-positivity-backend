package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.LaborGuideImportChunkEntity;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LaborGuideImportChunkRepository extends JpaRepository<LaborGuideImportChunkEntity, UUID> {

    boolean existsByImportManifestIdAndChunkSequence(@NonNull UUID importManifestId, int chunkSequence);

    long countByImportManifestId(@NonNull UUID importManifestId);
}
