package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.SourcingStrategy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Sourcing strategy engine (odoo-parity H1, issue #1037): given a SKU, an
 * optional site scope, and candidate locations, returns the candidates
 * ordered per the resolved {@link SourcingStrategy}.
 *
 * <p>Strategy resolution precedence: active SKU_CATEGORY config (when the
 * SKU's category is resolvable — see {@link SkuCategoryProvider}) → active
 * SITE config → active DEFAULT config → platform default FIFO.
 *
 * <p>Consumers: consumption allocation-close ordering
 * ({@code ConsumptionServiceImpl}), pick-task location suggestion
 * ({@code PickListGenerationServiceImpl}), replenishment source selection
 * (odoo-parity F5 via {@link #selectSource}), and lot suggestion (E2).
 */
public interface SourcingStrategyService {

    /**
     * Orders the selection's candidate locations per the resolved strategy.
     * The decision records both the configured and the effective strategy
     * (they differ when FEFO falls back to FIFO for lack of lot data, or
     * PROXIMITY falls back to FIFO for lack of a reference location).
     */
    @NonNull
    SourcingDecision orderCandidates(@NonNull SourcingSelection selection);

    /**
     * Picks the first candidate, in resolved-strategy order, whose available
     * quantity covers {@code neededQuantity} (the "source with surplus" shape
     * odoo-parity F5 replenishment source selection calls). Candidates
     * without a provided quantity are looked up in the stock summary. Empty
     * when no candidate has enough available stock.
     */
    @NonNull
    Optional<SourceSelection> selectSource(@NonNull SourcingSelection selection, long neededQuantity);

    /**
     * One sourcing question: order these candidate locations for this SKU.
     *
     * @param stockItemId ledger stock-item id (product UUID rendered as text)
     * @param siteId site for SITE-scoped config resolution; when null the
     *     engine derives it from the first candidate's storage-location
     *     replica ({@code ext_storage_location.site_id}) if replicated
     * @param referenceLocationId PROXIMITY anchor (e.g. the picker's current
     *     or scanned location); null makes PROXIMITY fall back to FIFO
     * @param candidates candidate locations; {@code availableQuantity} may be
     *     null, in which case strategies needing it read the stock summary
     */
    record SourcingSelection(
            @NonNull String stockItemId,
            @Nullable UUID siteId,
            @Nullable UUID referenceLocationId,
            @NonNull List<SourcingCandidate> candidates) {}

    /** One candidate location, with its available quantity when the caller already knows it. */
    record SourcingCandidate(
            @NonNull UUID locationId, @Nullable Long availableQuantity) {}

    /**
     * Ordering decision: the configured strategy, the effective strategy that
     * actually ordered the list (recorded as {@code sourcingReason} on pick
     * and replenishment tasks — spec H2), and the ordered candidates.
     */
    record SourcingDecision(
            @NonNull SourcingStrategy configuredStrategy,
            @NonNull SourcingStrategy effectiveStrategy,
            @NonNull List<SourcingCandidate> orderedCandidates) {}

    /** A source pick for F5: the chosen candidate plus the decision that produced it. */
    record SourceSelection(
            @NonNull SourcingCandidate candidate, @NonNull SourcingDecision decision) {}
}
