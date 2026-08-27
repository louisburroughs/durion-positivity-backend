package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.StorageCompatibility;
import com.positivity.inventory.internal.enums.StorageCompatibilityMatchLevel;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only access to the Flyway-seeded storage compatibility matrix (issue #1514). */
public interface StorageCompatibilityRepository extends JpaRepository<StorageCompatibility, UUID> {

    /**
     * The rows for one catalog reference at one level. An empty result at {@code SUBCATEGORY} means
     * "no override" and the caller falls through to {@code CATEGORY}; an empty result at
     * {@code CATEGORY} means the class is unknown to the matrix.
     */
    List<StorageCompatibility> findByMatchLevelAndCatalogRefId(
            StorageCompatibilityMatchLevel matchLevel, UUID catalogRefId);
}
