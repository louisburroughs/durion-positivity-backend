package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.SupplierPriceImportChunkEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Applied-chunk log per PRICAT import; the guard against re-applying a re-emitted chunk. */
public interface SupplierPriceImportChunkRepository extends JpaRepository<SupplierPriceImportChunkEntity, UUID> {

    boolean existsByImportManifestIdAndChunkSequence(UUID importManifestId, int chunkSequence);

    long countByImportManifestId(UUID importManifestId);
}
