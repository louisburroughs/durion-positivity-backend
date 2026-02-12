package com.positivity.accounting.service;

import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for GL Mapping resolution.
 * Resolves external source codes to GL accounts using effective-dated mappings
 * with dimensional matching and validation.
 *
 * Used during event ingestion to automatically post journal entries based on
 * mapping rules that account for:
 * - Temporal validity (effective start/end dates)
 * - Dimensional context (cost center, business unit, location, etc.)
 * - Source system compatibility
 *
 * Key Business Rules:
 * - Non-overlapping effective date ranges per source/code
 * - Dimension matching follows dimensional hierarchy (more specific → more
 * general)
 * - Fallback mappings for unmapped dimensions
 * - Audit trail of all resolution attempts
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class GLMappingResolver {

    /**
     * Dimensional hierarchy from least specific to most specific.
     * During fallback, dimensions are removed from most specific (last) first.
     */
    private static final List<String> DIMENSION_HIERARCHY = List.of(
            "businessUnitId", "locationId", "departmentId", "costCenterId");

    private final GLMappingRepository mappingRepository;

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
    public UUID resolveGLAccount(UUID postingCategoryId, UUID mappingKeyId,
            LocalDateTime transactionDate,
            Map<String, String> dimensionContext) {

        // Attempt resolution in order of specificity
        // 1. Try exact dimensional match
        Optional<UUID> exactMatch = resolveWithDimensions(
                postingCategoryId, mappingKeyId, transactionDate, dimensionContext);
        if (exactMatch.isPresent()) {
            log.debug("GL mapping resolved: {}/{} -> {} [exact dimensional match]",
                    postingCategoryId, mappingKeyId, exactMatch.get());
            return exactMatch.get();
        }

        // 2. Try progressive dimensional fallback (remove least specific dimensions)
        Optional<UUID> partialMatch = resolveFallback(
                postingCategoryId, mappingKeyId, transactionDate, dimensionContext);
        if (partialMatch.isPresent()) {
            log.debug("GL mapping resolved: {}/{} -> {} [partial dimensional match]",
                    postingCategoryId, mappingKeyId, partialMatch.get());
            return partialMatch.get();
        }

        // 3. Try category default (no dimensions)
        Optional<GLMapping> defaultMapping = mappingRepository
                .findEffectiveMapping(postingCategoryId, mappingKeyId, transactionDate);
        if (defaultMapping.isPresent()) {
            log.debug("GL mapping resolved: {}/{} -> {} [category default]",
                    postingCategoryId, mappingKeyId, defaultMapping.get().getGlAccountId());
            return defaultMapping.get().getGlAccountId();
        }

        // No mapping found
        String msg = String.format(
                "No GL mapping found for %s:%s on %s", postingCategoryId, mappingKeyId, transactionDate);
        log.warn(msg);
        throw new IllegalArgumentException(msg);
    }

    /**
     * Attempt resolution with exact dimensional matching.
     * All dimensions in the mapping must exactly equal the provided context.
     * Among matches, the mapping with the most dimensions wins (most specific).
     */
    private Optional<UUID> resolveWithDimensions(UUID postingCategoryId, UUID mappingKeyId,
            LocalDateTime transactionDate,
            Map<String, String> dimensionContext) {
        if (dimensionContext == null || dimensionContext.isEmpty()) {
            return Optional.empty();
        }

        List<GLMapping> candidates = mappingRepository.findAllEffectiveMappings(
                postingCategoryId, mappingKeyId, transactionDate);

        return candidates.stream()
                .filter(m -> m.getDimensions() != null && !m.getDimensions().isEmpty())
                .filter(m -> dimensionContext.equals(m.getDimensions()))
                .max((a, b) -> Integer.compare(
                        a.getDimensions().size(),
                        b.getDimensions().size()))
                .map(match -> {
                    log.debug("Exact dimensional match found: mapping={}, dimensions={}",
                            match.getGlMappingId(), match.getDimensions());
                    return match.getGlAccountId();
                });
    }

    /**
     * Attempt resolution with dimensional fallback.
     * Progressively removes the most-specific dimension and checks for a mapping
     * whose dimensions exactly match the reduced context.
     *
     * Removal order (most specific first): costCenterId → departmentId → locationId
     * → businessUnitId.
     *
     * Among matches at the same fallback level, the mapping with the most
     * dimensions wins.
     */
    private Optional<UUID> resolveFallback(UUID postingCategoryId, UUID mappingKeyId,
            LocalDateTime transactionDate,
            Map<String, String> dimensionContext) {
        if (dimensionContext == null || dimensionContext.isEmpty()) {
            return Optional.empty();
        }

        List<GLMapping> candidates = mappingRepository.findAllEffectiveMappings(
                postingCategoryId, mappingKeyId, transactionDate);

        // Only consider mappings that have dimensions (non-null, non-empty)
        List<GLMapping> dimensioned = candidates.stream()
                .filter(m -> m.getDimensions() != null && !m.getDimensions().isEmpty())
                .toList();

        if (dimensioned.isEmpty()) {
            return Optional.empty();
        }

        // Build a mutable copy of the context, then strip dimensions from
        // most-specific to least-specific until we find a match
        Map<String, String> reducedContext = new HashMap<>(dimensionContext);

        // Iterate from most-specific (last in hierarchy) to least-specific (first)
        for (int i = DIMENSION_HIERARCHY.size() - 1; i >= 0; i--) {
            String dimensionToRemove = DIMENSION_HIERARCHY.get(i);
            if (reducedContext.remove(dimensionToRemove) == null) {
                // This dimension wasn't in the context; skip to next
                continue;
            }

            if (reducedContext.isEmpty()) {
                // All dimensions removed — category default handled by caller
                break;
            }

            // Check if any mapping's dimensions match the reduced context
            Map<String, String> snapshot = Map.copyOf(reducedContext);
            Optional<GLMapping> match = dimensioned.stream()
                    .filter(m -> snapshot.equals(m.getDimensions()))
                    .max((a, b) -> Integer.compare(
                            a.getDimensions().size(),
                            b.getDimensions().size()));

            if (match.isPresent()) {
                log.debug("Dimensional fallback match found after removing '{}': mapping={}, dimensions={}",
                        dimensionToRemove, match.get().getGlMappingId(), match.get().getDimensions());
                return Optional.of(match.get().getGlAccountId());
            }
        }

        return Optional.empty();
    }

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
    public boolean validateMapping(GLMapping mapping) {
        if (mapping.getPostingCategoryId() == null) {
            throw new IllegalArgumentException("Posting category ID is required");
        }
        if (mapping.getMappingKeyId() == null) {
            throw new IllegalArgumentException("Mapping key ID is required");
        }
        if (mapping.getGlAccountId() == null) {
            throw new IllegalArgumentException("GL account ID is required");
        }
        if (mapping.getEffectiveStartDate() == null) {
            throw new IllegalArgumentException("Effective from date is required");
        }
        if (mapping.getEffectiveEndDate() != null
                && mapping.getEffectiveStartDate().isAfter(mapping.getEffectiveEndDate())) {
            throw new IllegalArgumentException(
                    "Effective from date must be on or before effective to date");
        }

        // Check for overlaps with existing mappings (same category + key combination)
        // Overlapping if: (existing.effectiveFrom <= mapping.effectiveTo OR
        // mapping.effectiveTo is null)
        // AND (existing.effectiveTo >= mapping.effectiveFrom OR existing.effectiveTo is
        // null)
        var overlaps = mappingRepository.findOverlappingMappings(
                mapping.getSourceSystem(),
                mapping.getExternalCode(),
                mapping.getEffectiveStartDate(),
                mapping.getEffectiveEndDate(),
                mapping.getGlMappingId() != null ? mapping.getGlMappingId() : new UUID(0L, 0L));

        if (!overlaps.isEmpty()) {
            String msg = String.format(
                    "Mapping has overlapping effective dates with existing mapping(s) for category=%s, key=%s",
                    mapping.getPostingCategoryId(), mapping.getMappingKeyId());
            log.warn(msg);
            throw new IllegalArgumentException(msg);
        }

        return true;
    }

    /**
     * Create or update a GL mapping with validation.
     *
     * @param mapping mapping to save
     * @return saved mapping
     * @throws IllegalArgumentException if mapping validation fails
     */
    public GLMapping saveMapping(GLMapping mapping) {
        validateMapping(mapping);
        return mappingRepository.save(mapping);
    }

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
    public UUID getEffectiveAccount(UUID postingCategoryId, UUID mappingKeyId,
            LocalDateTime transactionDate) {
        return mappingRepository.findEffectiveMapping(postingCategoryId, mappingKeyId, transactionDate)
                .map(GLMapping::getGlAccountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No effective mapping for " + postingCategoryId + ":" + mappingKeyId + " on "
                                + transactionDate));
    }

    /**
     * Get mapping history for audit trail.
     * Returns all versions (past and present) of a mapping key within a category.
     *
     * @param postingCategoryId posting category ID
     * @param mappingKeyId      mapping key ID
     * @return list of all historical mappings sorted by effective date
     */
    public java.util.List<GLMapping> getMappingHistory(UUID postingCategoryId, UUID mappingKeyId) {
        return mappingRepository.findByPostingCategoryId(postingCategoryId)
                .stream()
                .filter(m -> mappingKeyId.equals(m.getMappingKeyId()))
                .sorted((a, b) -> a.getEffectiveStartDate().compareTo(b.getEffectiveStartDate()))
                .toList();
    }
}
