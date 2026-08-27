package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.ValidationResult;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.PutawayDestinationStrategy;
import com.positivity.inventory.internal.enums.PutawayFallbackReason;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import com.positivity.inventory.internal.exception.LocationAtCapacityException;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.PutawayTaskRepository;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingCandidate;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingSelection;
import com.positivity.inventory.service.PutawayValidationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves the suggested destination for a putaway line against a matched
 * {@link PutawayRule}'s {@link PutawayDestinationStrategy} (odoo-parity K2,
 * issue #1055).
 *
 * <p>The engine reuses the H1 proximity machinery — it never re-implements
 * topology traversal. {@link PutawayDestinationStrategy#CLOSEST_AVAILABLE}
 * ranks candidate bins with {@link ProximitySourcingStrategy} (BFS-hop over the
 * replicated location tree) and gates each on the existing
 * {@link PutawayValidationService#validateLocationCapacity capacity checks},
 * skipping bins at or over capacity.
 *
 * <p>Fallback semantics (the K2 fallback chain): when a strategy's first choice
 * cannot be honored the resolver returns the rule's fixed
 * {@code destinationLocationId} (or the next-nearest bin, for
 * {@code CLOSEST_AVAILABLE}) and records why via
 * {@link ResolvedDestination#fallbackReason()}. {@link PutawayDestinationStrategy#FIXED}
 * always returns the rule's destination with no fallback — byte-identical to
 * pre-K2 behavior.
 */
@Component
@RequiredArgsConstructor
public class PutawayDestinationResolver {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final PutawayTaskRepository putawayTaskRepository;
    private final ExtStorageLocationReplicaRepository extStorageLocationReplicaRepository;
    private final ProximitySourcingStrategy proximitySourcingStrategy;
    private final PutawayValidationService putawayValidationService;

    /**
     * Resolves the destination for a received line under the matched rule.
     *
     * @param rule the winning (highest-priority enabled) putaway rule
     * @param productId the SKU being put away (LAST_USED / CLOSEST_AVAILABLE scope key)
     * @param quantity the quantity being put away (capacity gating for CLOSEST_AVAILABLE)
     */
    public @NonNull ResolvedDestination resolve(@NonNull PutawayRule rule, @NonNull UUID productId, int quantity) {
        PutawayDestinationStrategy strategy = rule.getDestinationStrategy() != null
                ? rule.getDestinationStrategy()
                : PutawayDestinationStrategy.FIXED;
        return switch (strategy) {
            case FIXED -> fixed(rule);
            case LAST_USED -> resolveLastUsed(rule, productId);
            case CLOSEST_AVAILABLE -> resolveClosestAvailable(rule, productId, quantity);
        };
    }

    private ResolvedDestination fixed(PutawayRule rule) {
        return new ResolvedDestination(rule.getDestinationLocationId(), null, null);
    }

    private ResolvedDestination resolveLastUsed(PutawayRule rule, UUID productId) {
        return putawayTaskRepository
                .findFirstByProductIdAndStatusAndActualDestinationLocationIdIsNotNullOrderByUpdatedAtDesc(
                        productId, PutawayTaskStatus.COMPLETED)
                .map(PutawayTask::getActualDestinationLocationId)
                .map(lastUsed -> new ResolvedDestination(lastUsed, null, null))
                // Never put away for this SKU yet: fall back to the rule's fixed destination.
                .orElseGet(() -> new ResolvedDestination(
                        rule.getDestinationLocationId(), null, PutawayFallbackReason.UNAVAILABLE));
    }

    private ResolvedDestination resolveClosestAvailable(PutawayRule rule, UUID productId, int quantity) {
        UUID anchor = rule.getDestinationLocationId();

        UUID siteId = extStorageLocationReplicaRepository
                .findById(anchor)
                .map(ExtStorageLocationReplica::getSiteId)
                .orElse(null);
        if (siteId == null) {
            // Topology for the anchor is unknown; degrade to the fixed destination.
            return fixed(rule);
        }

        // Staging floors and quarantine cages are ACTIVE storage locations at the site, so without
        // this filter the proximity search can offer one as an overflow hop. It would pass the
        // capacity gate and then be refused by compatibility, failing the receipt on a destination
        // the system picked itself. Skip them before they are ever proposed (#1514).
        List<SourcingCandidate> candidates = extStorageLocationReplicaRepository.findBySiteId(siteId).stream()
                .filter(replica -> STATUS_ACTIVE.equalsIgnoreCase(replica.getStatus()))
                .filter(replica -> !StorageCompatibilityEvaluator.isSourceOnly(replica.getStorageCategoryCode()))
                .map(replica -> new SourcingCandidate(replica.getStorageLocationId(), null))
                .toList();
        if (candidates.isEmpty()) {
            return fixed(rule);
        }

        List<SourcingCandidate> ordered = proximitySourcingStrategy.order(
                new SourcingSelection(productId.toString(), siteId, anchor, candidates));
        UUID nearest = ordered.get(0).locationId();

        for (SourcingCandidate candidate : ordered) {
            UUID candidateId = candidate.locationId();
            if (hasCapacity(candidateId, quantity)) {
                boolean fellBack = !candidateId.equals(nearest);
                return new ResolvedDestination(
                        candidateId,
                        fellBack ? nearest : null,
                        fellBack ? PutawayFallbackReason.DESTINATION_FULL : null);
            }
        }

        // Every nearby bin is full: fall back to the rule's fixed destination.
        return new ResolvedDestination(anchor, nearest, PutawayFallbackReason.DESTINATION_FULL);
    }

    private boolean hasCapacity(UUID locationId, int quantity) {
        try {
            ValidationResult result = putawayValidationService.validateLocationCapacity(locationId, quantity);
            // Fail closed: validateLocationCapacity is contractually non-null, so a null here means
            // a broken contract (bad mock / future refactor) — treat the bin as unavailable rather
            // than silently admitting a possibly over-capacity destination.
            return result != null && result.isValid();
        } catch (LocationAtCapacityException e) {
            return false;
        } catch (IllegalArgumentException e) {
            // Inactive or unknown storage location — not a valid destination.
            return false;
        }
    }

    /**
     * Resolved putaway destination.
     *
     * @param destinationLocationId the bin the task should suggest
     * @param originalSuggestedLocationId the strategy's first choice when a
     *     fallback occurred, else {@code null}
     * @param fallbackReason why the first choice was not honored, else
     *     {@code null} (no fallback)
     */
    public record ResolvedDestination(
            @NonNull UUID destinationLocationId,
            @Nullable UUID originalSuggestedLocationId,
            @Nullable PutawayFallbackReason fallbackReason) {

        public boolean isFallback() {
            return fallbackReason != null;
        }

        public @NonNull Optional<UUID> originalSuggestedLocationIdOptional() {
            return Optional.ofNullable(originalSuggestedLocationId);
        }
    }
}
