package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.GLMapping;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Repository for GL Mapping entity.
 * Supports temporal effective-dated queries, dimension lookups, and overlap
 * detection.
 */
@Repository
public interface GLMappingRepository extends JpaRepository<GLMapping, UUID> {

    /**
     * Find all mappings for a source system.
     */
    List<GLMapping> findBySourceSystem(String sourceSystem);

    /**
     * Find a GL mapping by source system and external code.
     */
    Optional<GLMapping> findBySourceSystemAndExternalCode(String sourceSystem, String externalCode);

    /**
     * Find all GL mappings effective at a specific transaction date.
     * Supports effective-dated lookups for GL account resolution.
     */
    @Query("SELECT glm FROM GLMapping glm " + "WHERE glm.sourceSystem = :sourceSystem "
            + "AND glm.externalCode = :externalCode "
            + "AND glm.effectiveStartDate <= :transactionDate "
            + "AND (glm.effectiveEndDate IS NULL OR glm.effectiveEndDate > :transactionDate)")
    Optional<GLMapping> findEffectiveMapping(String sourceSystem, String externalCode, LocalDateTime transactionDate);

    /**
     * Find GL mapping by posting category and mapping key effective at a specific
     * date.
     * Supports effective-dated lookups for GL account resolution by category/key.
     */
    @Query("SELECT glm FROM GLMapping glm " + "WHERE glm.postingCategory.postingCategoryId = :postingCategoryId "
            + "AND glm.mappingKey.mappingKeyId = :mappingKeyId "
            + "AND glm.effectiveStartDate <= :transactionDate "
            + "AND (glm.effectiveEndDate IS NULL OR glm.effectiveEndDate > :transactionDate)")
    Optional<GLMapping> findEffectiveMapping(UUID postingCategoryId, UUID mappingKeyId, LocalDateTime transactionDate);

    /**
     * Find all GL mappings by posting category and mapping key effective at a
     * specific date.
     * Returns all candidates (with and without dimensions) for in-memory
     * dimensional matching.
     */
    @Query("SELECT glm FROM GLMapping glm " + "WHERE glm.postingCategory.postingCategoryId = :postingCategoryId "
            + "AND glm.mappingKey.mappingKeyId = :mappingKeyId "
            + "AND glm.effectiveStartDate <= :transactionDate "
            + "AND (glm.effectiveEndDate IS NULL OR glm.effectiveEndDate > :transactionDate)")
    List<GLMapping> findAllEffectiveMappings(UUID postingCategoryId, UUID mappingKeyId, LocalDateTime transactionDate);

    /**
     * Find all active mappings (no end date or end date in future).
     */
    @Query(
            "SELECT glm FROM GLMapping glm WHERE glm.effectiveEndDate IS NULL OR glm.effectiveEndDate > CURRENT_TIMESTAMP")
    List<GLMapping> findActiveMappings();

    /**
     * Detect overlapping mappings (same source/code, overlapping date ranges).
     * Used to enforce non-overlapping effective dates.
     */
    @Query("SELECT glm FROM GLMapping glm " + "WHERE glm.sourceSystem = :sourceSystem "
            + "AND glm.externalCode = :externalCode "
            + "AND glm.id != :excludeId "
            + "AND glm.effectiveStartDate <= :endDate "
            + "AND (glm.effectiveEndDate IS NULL OR glm.effectiveEndDate > :startDate)")
    List<GLMapping> findOverlappingMappings(
            String sourceSystem, String externalCode, LocalDateTime startDate, LocalDateTime endDate, UUID excludeId);

    /**
     * Find all mappings for a GL account.
     */
    List<GLMapping> findByGlAccount_GlAccountId(UUID glAccountId);

    List<GLMapping> findByPostingCategory_PostingCategoryId(UUID postingCategoryId);

    /**
     * Count active mappings for a posting category.
     * Active mappings are those with no end date or end date in the future.
     */
    @Query("SELECT COUNT(glm) FROM GLMapping glm " + "WHERE glm.postingCategory.postingCategoryId = :postingCategoryId "
            + "AND (glm.effectiveEndDate IS NULL OR glm.effectiveEndDate > CURRENT_TIMESTAMP)")
    long countByPostingCategoryIdAndDeactivatedAtIsNull(UUID postingCategoryId);

    /**
     * Count active mappings for a mapping key.
     * Active mappings are those with no end date or end date in the future.
     */
    @Query("SELECT COUNT(glm) FROM GLMapping glm " + "WHERE glm.mappingKey.mappingKeyId = :mappingKeyId "
            + "AND (glm.effectiveEndDate IS NULL OR glm.effectiveEndDate > CURRENT_TIMESTAMP)")
    long countByMappingKeyIdAndDeactivatedAtIsNull(UUID mappingKeyId);
}
