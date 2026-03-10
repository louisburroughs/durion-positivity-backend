package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.MappingKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Mapping Key entity.
 * Supports querying mapping keys by posting category and name.
 */
@Repository
public interface MappingKeyRepository extends JpaRepository<MappingKey, UUID>, JpaSpecificationExecutor<MappingKey> {

    /**
     * Find a mapping key by posting category and key name.
     */
    Optional<MappingKey> findByPostingCategory_PostingCategoryIdAndKeyName(UUID postingCategoryId, String keyName);

    /**
     * Find all mapping keys for a posting category.
     */
    List<MappingKey> findByPostingCategory_PostingCategoryId(UUID postingCategoryId);

    /**
     * Find all active mapping keys.
     */
    List<MappingKey> findByIsActive(Boolean isActive);

    /**
     * Check if a mapping key name already exists within a category.
     */
    boolean existsByPostingCategory_PostingCategoryIdAndKeyName(UUID postingCategoryId, String keyName);
}
