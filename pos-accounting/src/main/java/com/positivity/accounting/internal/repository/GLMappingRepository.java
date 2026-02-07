package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.GLMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
        @Query("SELECT glm FROM GLMapping glm " +
                        "WHERE glm.sourceSystem = :sourceSystem " +
                        "AND glm.externalCode = :externalCode " +
                        "AND glm.effectiveStartDate <= :transactionDate " +
                        "AND (glm.effectiveEndDate IS NULL OR glm.effectiveEndDate > :transactionDate)")
        Optional<GLMapping> findEffectiveMapping(String sourceSystem, String externalCode,
                        LocalDateTime transactionDate);

        /**
         * Find GL mapping by posting category and mapping key effective at a specific
         * date.
         * Supports effective-dated lookups for GL account resolution by category/key.
         */
        @Query("SELECT glm FROM GLMapping glm " +
                        "WHERE glm.postingCategoryId = :postingCategoryId " +
                        "AND glm.mappingKeyId = :mappingKeyId " +
                        "AND glm.effectiveStartDate <= :transactionDate " +
                        "AND (glm.effectiveEndDate IS NULL OR glm.effectiveEndDate > :transactionDate)")
        Optional<GLMapping> findEffectiveMapping(UUID postingCategoryId, UUID mappingKeyId,
                        LocalDateTime transactionDate);

        /**
         * Find all active mappings (no end date or end date in future).
         */
        @Query("SELECT glm FROM GLMapping glm WHERE glm.effectiveEndDate IS NULL OR glm.effectiveEndDate > CURRENT_TIMESTAMP")
        List<GLMapping> findActiveMappings();

        /**
         * Detect overlapping mappings (same source/code, overlapping date ranges).
         * Used to enforce non-overlapping effective dates.
         */
        @Query("SELECT glm FROM GLMapping glm " +
                        "WHERE glm.sourceSystem = :sourceSystem " +
                        "AND glm.externalCode = :externalCode " +
                        "AND glm.id != :excludeId " +
                        "AND glm.effectiveStartDate <= :endDate " +
                        "AND (glm.effectiveEndDate IS NULL OR glm.effectiveEndDate > :startDate)")
        List<GLMapping> findOverlappingMappings(String sourceSystem, String externalCode,
                        LocalDateTime startDate, LocalDateTime endDate, UUID excludeId);

        /**
         * Find all mappings for a GL account.
         */
        List<GLMapping> findByGlAccountId(UUID glAccountId);

        List<GLMapping> findByPostingCategoryId(UUID postingCategoryId);
}
