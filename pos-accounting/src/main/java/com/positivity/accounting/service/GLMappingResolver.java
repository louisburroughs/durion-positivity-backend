package com.positivity.accounting.service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import com.positivity.accounting.internal.entity.GLMapping;

public interface GLMappingResolver {

    /**
     * Resolves a posting category and mapping key to a GL account.
     * Returns the most specific mapping based on dimensional context and effective
     * date.
     *
     * Resolution Priority (first match wins):
     * 1. Exact match: postingCategoryId + mappingKeyId + dimensions + transaction
     * date
     * 2. Dimensional fallback: postingCategoryId + mappingKeyId + partial
     * dimensions + date
     * 3. Category default: postingCategoryId + mappingKeyId + no dimensions + date
     *
     * @param postingCategoryId posting category identifier
     * @param mappingKeyId      mapping key to resolve
     * @param transactionDate   effective date for resolution (LocalDateTime)
     * @param dimensionContext  dimensional context (businessUnitId, locationId,
     *                          etc.)
     * @return GL account ID for posting
     * @throws IllegalArgumentException if no valid mapping found
     */
    UUID resolveGLAccount(UUID postingCategoryId, UUID mappingKeyId,
            LocalDateTime transactionDate,
            Map<String, String> dimensionContext);

    /**
     * Validates a GL mapping for consistency and non-overlapping effective dates.
     *
     * Checks:
     * - GL account exists and is ACTIVE
     * - Effective date ranges are valid
     * - No overlapping mappings for same source/code combination
     * - Dimensions are recognized and consistent
     *
     * @param mapping mapping to validate
     * @return true if valid, throws exception otherwise
     * @throws IllegalArgumentException if mapping is invalid
     */
    boolean validateMapping(GLMapping mapping);

    /**
     * Create or update a GL mapping with validation.
     *
     * @param mapping mapping to save
     * @return saved mapping
     * @throws IllegalArgumentException if mapping validation fails
     */
    GLMapping saveMapping(GLMapping mapping);

    /**
     * Get the effective GL account for a posting category and mapping key at a
     * specific date.
     * Useful for audit and debugging.
     *
     * @param postingCategoryId posting category ID
     * @param mappingKeyId      mapping key ID
     * @param transactionDate   effective date (LocalDateTime)
     * @return effective GL account ID
     */
    UUID getEffectiveAccount(UUID postingCategoryId, UUID mappingKeyId,
            LocalDateTime transactionDate);

    /**
     * Get mapping history for audit trail.
     * Returns all versions (past and present) of a mapping key within a category.
     *
     * @param postingCategoryId posting category ID
     * @param mappingKeyId      mapping key ID
     * @return list of all historical mappings sorted by effective date
     */
    java.util.List<GLMapping> getMappingHistory(UUID postingCategoryId, UUID mappingKeyId);

}