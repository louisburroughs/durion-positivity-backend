package com.positivity.accounting.repository;

import com.positivity.accounting.entity.MappingKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Mapping Key entity.
 * Supports querying mapping keys by posting category and name.
 */
@Repository
public interface MappingKeyRepository extends JpaRepository<MappingKey, String> {

    /**
     * Find a mapping key by posting category and key name.
     */
    Optional<MappingKey> findByPostingCategoryIdAndKeyName(String postingCategoryId, String keyName);

    /**
     * Find all mapping keys for a posting category.
     */
    List<MappingKey> findByPostingCategoryId(String postingCategoryId);

    /**
     * Find all active mapping keys.
     */
    List<MappingKey> findByIsActive(Boolean isActive);

    /**
     * Check if a mapping key name already exists within a category.
     */
    boolean existsByPostingCategoryIdAndKeyName(String postingCategoryId, String keyName);
}
