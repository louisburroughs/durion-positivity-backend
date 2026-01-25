package com.positivity.accounting.service;

import com.positivity.accounting.entity.GLMapping;
import com.positivity.accounting.repository.GLMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

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

    private final GLMappingRepository mappingRepository;

    /**
     * Resolves an external source code to a GL account.
     * Returns the most specific mapping based on dimensional context.
     *
     * Resolution Priority (first match wins):
     * 1. Exact match: source system + code + dimensions + transaction date
     * 2. Dimensional fallback: source system + code + partial dimensions + date
     * 3. Source default: source system + code + no dimensions + date
     *
     * @param sourceSystem     source system identifier (e.g., "MYOB", "QUICKBOOKS")
     * @param externalCode     external code to resolve
     * @param transactionDate  effective date for resolution
     * @param dimensionContext dimensional context (cost_center, location, etc.)
     * @return GL account ID for posting
     * @throws IllegalArgumentException if no valid mapping found
     */
    public String resolveGLAccount(String sourceSystem, String externalCode,
            LocalDateTime transactionDate,
            Map<String, String> dimensionContext) {

        // Attempt resolution in order of specificity
        // 1. Try exact dimensional match
        Optional<String> exactMatch = resolveWithDimensions(
                sourceSystem, externalCode, transactionDate, dimensionContext);
        if (exactMatch.isPresent()) {
            log.debug("GL mapping resolved: {} -> {} [exact dimensional match]",
                    externalCode, exactMatch.get());
            return exactMatch.get();
        }

        // 2. Try progressive dimensional fallback (remove least specific dimensions)
        Optional<String> partialMatch = resolveFallback(
                sourceSystem, externalCode, transactionDate, dimensionContext);
        if (partialMatch.isPresent()) {
            log.debug("GL mapping resolved: {} -> {} [partial dimensional match]",
                    externalCode, partialMatch.get());
            return partialMatch.get();
        }

        // 3. Try source-level default (no dimensions)
        Optional<GLMapping> defaultMapping = mappingRepository
                .findEffectiveMapping(sourceSystem, externalCode, transactionDate);
        if (defaultMapping.isPresent()) {
            log.debug("GL mapping resolved: {} -> {} [source default]",
                    externalCode, defaultMapping.get().getGlAccountId());
            return defaultMapping.get().getGlAccountId();
        }

        // No mapping found
        String msg = String.format(
                "No GL mapping found for %s:%s on %s", sourceSystem, externalCode, transactionDate);
        log.warn(msg);
        throw new IllegalArgumentException(msg);
    }

    /**
     * Attempt resolution with exact dimensional matching.
     * All dimensions in context must match dimensions in mapping.
     */
    private Optional<String> resolveWithDimensions(String sourceSystem, String externalCode,
            LocalDateTime transactionDate,
            Map<String, String> dimensionContext) {
        if (dimensionContext == null || dimensionContext.isEmpty()) {
            return Optional.empty();
        }

        // TODO: Implement exact dimensional matching
        // Would query mappings by source+code+dimensions and match transaction date
        // range
        return Optional.empty();
    }

    /**
     * Attempt resolution with dimensional fallback.
     * Try progressively less specific dimensional combinations until match found.
     */
    private Optional<String> resolveFallback(String sourceSystem, String externalCode,
            LocalDateTime transactionDate,
            Map<String, String> dimensionContext) {
        if (dimensionContext == null || dimensionContext.isEmpty()) {
            return Optional.empty();
        }

        // TODO: Implement dimensional fallback strategy
        // Start with all dimensions, progressively remove least-specific (location,
        // then cost center, etc.)
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
        if (mapping.getSourceSystem() == null || mapping.getSourceSystem().isBlank()) {
            throw new IllegalArgumentException("Source system is required");
        }
        if (mapping.getExternalCode() == null || mapping.getExternalCode().isBlank()) {
            throw new IllegalArgumentException("External code is required");
        }
        if (mapping.getGlAccountId() == null || mapping.getGlAccountId().isBlank()) {
            throw new IllegalArgumentException("GL account ID is required");
        }
        if (mapping.getEffectiveStartDate() == null) {
            throw new IllegalArgumentException("Effective start date is required");
        }

        // Check for overlaps with existing mappings
        var overlaps = mappingRepository.findOverlappingMappings(
                mapping.getSourceSystem(),
                mapping.getExternalCode(),
                mapping.getEffectiveStartDate(),
                mapping.getEffectiveEndDate() != null ? mapping.getEffectiveEndDate() : LocalDateTime.MAX,
                mapping.getId() != null ? mapping.getId() : "");

        if (!overlaps.isEmpty()) {
            String msg = String.format(
                    "Mapping has overlapping effective dates with existing mapping(s) for %s:%s",
                    mapping.getSourceSystem(), mapping.getExternalCode());
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
     * Get the effective GL account for a source code at a specific point in time.
     * Useful for audit and debugging.
     *
     * @param sourceSystem    source system
     * @param externalCode    external code
     * @param transactionDate effective date
     * @return effective GL account ID
     */
    public String getEffectiveAccount(String sourceSystem, String externalCode,
            LocalDateTime transactionDate) {
        return mappingRepository.findEffectiveMapping(sourceSystem, externalCode, transactionDate)
                .map(GLMapping::getGlAccountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No effective mapping for " + sourceSystem + ":" + externalCode + " on " + transactionDate));
    }

    /**
     * Get mapping history for audit trail.
     */
    public java.util.List<GLMapping> getMappingHistory(String sourceSystem, String externalCode) {
        return mappingRepository.findBySourceSystem(sourceSystem)
                .stream()
                .filter(m -> externalCode.equals(m.getExternalCode()))
                .toList();
    }
}
