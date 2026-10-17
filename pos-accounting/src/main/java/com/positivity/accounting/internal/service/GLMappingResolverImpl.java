package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
// noRollbackFor (#2677): a GLMappingNotConfiguredException is this bean's answer, not a failure of the caller's
// transaction. Without it, the exception crossing this proxy marked a participating transaction rollback-only even
// when the caller caught it, and the caller's commit then failed with UnexpectedRollbackException (a bill read was a
// 500, a decision's writes were lost). A posting that lets the exception propagate still rolls back at its own
// boundary.
@Transactional(noRollbackFor = GLMappingNotConfiguredException.class)
public class GLMappingResolverImpl implements GLMappingResolver {

    /**
     * Dimensional hierarchy from least specific to most specific.
     * During fallback, dimensions are removed from most specific (last) first.
     */
    private static final List<String> DIMENSION_HIERARCHY =
            List.of("businessUnitId", "locationId", "departmentId", "costCenterId");

    private final GLMappingRepository mappingRepository;
    private final PostingCategoryRepository postingCategoryRepository;
    private final MappingKeyRepository mappingKeyRepository;

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
     * @throws GLMappingNotConfiguredException if no valid mapping found
     */
    @Override
    public UUID resolveGLAccount(
            UUID postingCategoryId,
            UUID mappingKeyId,
            LocalDateTime transactionDate,
            Map<String, String> dimensionContext) {
        requireActive(postingCategoryId, mappingKeyId);
        return resolveActive(postingCategoryId, mappingKeyId, transactionDate, dimensionContext);
    }

    private UUID resolveActive(
            UUID postingCategoryId,
            UUID mappingKeyId,
            LocalDateTime transactionDate,
            Map<String, String> dimensionContext) {

        // Attempt resolution in order of specificity
        // 1. Try exact dimensional match
        Optional<UUID> exactMatch =
                resolveWithDimensions(postingCategoryId, mappingKeyId, transactionDate, dimensionContext);
        if (exactMatch.isPresent()) {
            log.debug(
                    "GL mapping resolved: {}/{} -> {} [exact dimensional match]",
                    postingCategoryId,
                    mappingKeyId,
                    exactMatch.get());
            return exactMatch.get();
        }

        // 2. Try progressive dimensional fallback (remove least specific dimensions)
        Optional<UUID> partialMatch =
                resolveFallback(postingCategoryId, mappingKeyId, transactionDate, dimensionContext);
        if (partialMatch.isPresent()) {
            log.debug(
                    "GL mapping resolved: {}/{} -> {} [partial dimensional match]",
                    postingCategoryId,
                    mappingKeyId,
                    partialMatch.get());
            return partialMatch.get();
        }

        // 3. Try category default (no dimensions)
        Optional<GLMapping> defaultMapping =
                mappingRepository.findEffectiveMapping(postingCategoryId, mappingKeyId, transactionDate);
        if (defaultMapping.isPresent()) {
            log.debug(
                    "GL mapping resolved: {}/{} -> {} [category default]",
                    postingCategoryId,
                    mappingKeyId,
                    defaultMapping.get().getGlAccountId());
            return defaultMapping.get().getGlAccountId();
        }

        // No mapping found
        String msg =
                String.format("No GL mapping found for %s:%s on %s", postingCategoryId, mappingKeyId, transactionDate);
        log.warn(msg);
        throw new GLMappingNotConfiguredException(msg);
    }

    /**
     * Resolves a posting category and mapping key to a GL account by their
     * configured names (story C1, issue #954). No dimensional context —
     * resolution falls through to the category default mapping.
     *
     * @param postingCategoryName posting category name
     * @param mappingKeyName      mapping key name within the category
     * @param transactionDate     effective date for resolution
     * @return GL account ID for posting
     * @throws GLMappingNotConfiguredException if the category, key, or an
     *                                         effective mapping is not configured
     */
    @Override
    public UUID resolveGLAccount(String postingCategoryName, String mappingKeyName, LocalDateTime transactionDate) {
        PostingCategory category = postingCategoryRepository
                .findByCategoryName(postingCategoryName)
                .orElseThrow(() ->
                        new GLMappingNotConfiguredException("Posting category not configured: " + postingCategoryName));
        if (inactive(category)) {
            log.warn("GL mapping unresolved: posting category {} is inactive", postingCategoryName);
            throw new GLMappingNotConfiguredException("Posting category inactive: " + postingCategoryName);
        }

        MappingKey mappingKey = mappingKeyRepository
                .findByPostingCategory_PostingCategoryIdAndKeyName(category.getPostingCategoryId(), mappingKeyName)
                .orElseThrow(() -> new GLMappingNotConfiguredException(
                        "Mapping key not configured: " + postingCategoryName + "/" + mappingKeyName));
        if (inactive(mappingKey)) {
            log.warn("GL mapping unresolved: mapping key {}/{} is inactive", postingCategoryName, mappingKeyName);
            throw new GLMappingNotConfiguredException(
                    "Mapping key inactive: " + postingCategoryName + "/" + mappingKeyName);
        }

        return resolveActive(category.getPostingCategoryId(), mappingKey.getMappingKeyId(), transactionDate, Map.of());
    }

    /**
     * An inactive posting category or mapping key is unresolved on every posting path (#2612): it answers exactly as
     * a missing one, 422 {@code GL_MAPPING_NOT_CONFIGURED} (#2601, one code for every case), whatever the posting
     * date, so a back-dated posting through retired configuration is refused too: deactivation (which refuses while a
     * mapping is effective today) is the operator's statement that nothing posts there any more. One read: the key
     * with its category. A petty-expense category's deactivation keeps its mapping key active on purpose, so movements
     * recorded before it still post at close (S15 §4.6, never retroactive); that path is not affected.
     */
    private void requireActive(UUID postingCategoryId, UUID mappingKeyId) {
        inactiveReason(postingCategoryId, mappingKeyId).ifPresent(reason -> {
            log.warn("GL mapping unresolved: {}", reason);
            throw new GLMappingNotConfiguredException(reason);
        });
    }

    private Optional<String> inactiveReason(UUID postingCategoryId, UUID mappingKeyId) {
        return mappingKeyRepository.findWithCategoryById(mappingKeyId).flatMap(key -> {
            PostingCategory category = key.getPostingCategory();
            if (category != null && inactive(category)) {
                return Optional.of("Posting category inactive: " + category.getCategoryName());
            }
            if (inactive(key)) {
                return Optional.of("Mapping key inactive: " + key.getKeyName());
            }
            return Optional.empty();
        });
    }

    private static boolean inactive(PostingCategory category) {
        return Boolean.FALSE.equals(category.getIsActive());
    }

    private static boolean inactive(MappingKey key) {
        return Boolean.FALSE.equals(key.getIsActive());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads every mapping effective on the date and prefers the category default (no dimensions), the one the
     * name-based posting path falls through to; a key mapped only with dimensions answers its first mapping, as
     * that path does. Nothing is thrown, not for a miss and not for two effective rows, and nothing is logged at WARN:
     * the read is not a posting that failed.
     */
    @Override
    @Transactional(readOnly = true, noRollbackFor = GLMappingNotConfiguredException.class)
    public Optional<UUID> findGLAccount(
            @NonNull String postingCategoryName,
            @NonNull String mappingKeyName,
            @NonNull LocalDateTime transactionDate) {
        return postingCategoryRepository
                .findByCategoryName(postingCategoryName)
                .filter(category -> !inactive(category))
                .flatMap(category -> {
                    UUID categoryId = category.getPostingCategoryId();
                    return mappingKeyRepository
                            .findByPostingCategory_PostingCategoryIdAndKeyName(categoryId, mappingKeyName)
                            .filter(key -> !inactive(key))
                            .flatMap(key -> categoryDefaultFirst(mappingRepository.findAllEffectiveMappings(
                                    categoryId, key.getMappingKeyId(), transactionDate)));
                })
                .map(GLMapping::getGlAccountId);
    }

    private static Optional<GLMapping> categoryDefaultFirst(List<GLMapping> effective) {
        return effective.stream()
                .filter(m -> m.getDimensions() == null || m.getDimensions().isEmpty())
                .findFirst()
                .or(() -> effective.stream().findFirst());
    }

    /**
     * Attempt resolution with exact dimensional matching.
     * All dimensions in the mapping must exactly equal the provided context.
     * Among matches, the mapping with the most dimensions wins (most specific).
     */
    private Optional<UUID> resolveWithDimensions(
            UUID postingCategoryId,
            UUID mappingKeyId,
            LocalDateTime transactionDate,
            Map<String, String> dimensionContext) {
        if (dimensionContext == null || dimensionContext.isEmpty()) {
            return Optional.empty();
        }

        List<GLMapping> candidates =
                mappingRepository.findAllEffectiveMappings(postingCategoryId, mappingKeyId, transactionDate);

        return candidates.stream()
                .filter(m -> m.getDimensions() != null && !m.getDimensions().isEmpty())
                .filter(m -> dimensionContext.equals(m.getDimensions()))
                .max((a, b) -> Integer.compare(
                        a.getDimensions().size(), b.getDimensions().size()))
                .map(match -> {
                    log.debug(
                            "Exact dimensional match found: mapping={}, dimensions={}",
                            match.getGlMappingId(),
                            match.getDimensions());
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
    private Optional<UUID> resolveFallback(
            UUID postingCategoryId,
            UUID mappingKeyId,
            LocalDateTime transactionDate,
            Map<String, String> dimensionContext) {
        if (dimensionContext == null || dimensionContext.isEmpty()) {
            return Optional.empty();
        }

        List<GLMapping> candidates =
                mappingRepository.findAllEffectiveMappings(postingCategoryId, mappingKeyId, transactionDate);

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
                            a.getDimensions().size(), b.getDimensions().size()));

            if (match.isPresent()) {
                log.debug(
                        "Dimensional fallback match found after removing '{}': mapping={}, dimensions={}",
                        dimensionToRemove,
                        match.get().getGlMappingId(),
                        match.get().getDimensions());
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
    // (d) Defensive/internal invariant: GLMappingResolver.validateMapping is not called
    // anywhere in main (or by any test) — dead code on the interface, not reachable from a
    // controller. Left as IllegalArgumentException throughout this method; falls through to
    // the pos-web-common catch-all (correlated 500) if it is ever wired up without revisiting
    // this classification.
    @Override
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
        // A mapping against a retired category or key would resolve nothing (#2612); refuse it up front.
        inactiveReason(mapping.getPostingCategoryId(), mapping.getMappingKeyId())
                .ifPresent(reason -> {
                    throw new IllegalArgumentException(reason);
                });
        if (mapping.getEffectiveEndDate() != null
                && mapping.getEffectiveStartDate().isAfter(mapping.getEffectiveEndDate())) {
            throw new IllegalArgumentException("Effective from date must be on or before effective to date");
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
    @Override
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
    @Override
    public UUID getEffectiveAccount(UUID postingCategoryId, UUID mappingKeyId, LocalDateTime transactionDate) {
        // An audit read, deliberately without the active check (#2612): it answers which account a key posted to on
        // a date, which stays a fact after the key is retired. Posting goes through resolveGLAccount.
        return mappingRepository
                .findEffectiveMapping(postingCategoryId, mappingKeyId, transactionDate)
                .map(GLMapping::getGlAccountId)
                .orElseThrow(() -> new GLMappingNotConfiguredException("No effective mapping for " + postingCategoryId
                        + ":" + mappingKeyId + " on " + transactionDate));
    }

    /**
     * Get mapping history for audit trail.
     * Returns all versions (past and present) of a mapping key within a category.
     *
     * @param postingCategoryId posting category ID
     * @param mappingKeyId      mapping key ID
     * @return list of all historical mappings sorted by effective date
     */
    @Override
    public java.util.List<GLMapping> getMappingHistory(UUID postingCategoryId, UUID mappingKeyId) {
        return mappingRepository.findByPostingCategory_PostingCategoryId(postingCategoryId).stream()
                .filter(m -> mappingKeyId.equals(m.getMappingKeyId()))
                .sorted((a, b) -> a.getEffectiveStartDate().compareTo(b.getEffectiveStartDate()))
                .toList();
    }
}
