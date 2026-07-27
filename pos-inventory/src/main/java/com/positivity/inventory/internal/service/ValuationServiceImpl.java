package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.valuation.ValuationReportResponse;
import com.positivity.inventory.internal.dto.valuation.ValuationRow;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.SkuCostState;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.ValuationAsOfSkuCapExceededException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import com.positivity.inventory.service.ValuationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory valuation read model (odoo-parity J2, issue #1052).
 *
 * <p><strong>Current valuation.</strong> On-hand comes from the {@code inventory_stock_summary}
 * lot-agnostic rows (A1); the unit cost comes from each SKU's running {@code sku_cost_state} (J1)
 * derived per the SKU's resolved costing method — AVERAGE uses the running weighted-average,
 * STANDARD uses the configured standard price and falls back to the latest-receipt-cost memo. The
 * value read model is never persisted; it is computed from the two J1/A1 sources on demand.
 *
 * <p><strong>As-of valuation.</strong> Reconstructed from the ledger to mirror the A3 as-of
 * on-hand replay: on-hand is the summed on-hand-affecting delta up to the instant, and the unit
 * cost is reconstructed by replaying the SKU's on-hand-affecting entries through the same
 * {@link CostingStrategy} up to the instant. Because a receipt stamps its document unit cost on the
 * entry, feeding {@code entry.getUnitCost()} back as the document cost reproduces the running
 * average exactly; the standard price (a slowly-changing config value not carried on the ledger) is
 * seeded from the current cost state. So the reconstructed value equals the historical
 * Σ(qty × method-derived unit cost).
 *
 * <p>Uncosted SKUs (no cost state, or a STANDARD SKU with neither standard price nor receipt)
 * surface {@code unitCostCurrent = null}, {@code uncosted = true}, {@code onHandValue = 0} — never
 * an error.
 */
@Service
public class ValuationServiceImpl implements ValuationService {

    private static final int VALUE_SCALE = 4;

    private final InventoryStockSummaryRepository stockSummaryRepository;
    private final InventoryLedgerEntryRepository ledgerRepository;
    private final SkuCostStateRepository costStateRepository;
    private final CostingMethodResolver methodResolver;
    private final AsOfQueryGuard asOfQueryGuard;
    private final int asOfSkuCap;
    private final Map<CostingMethod, CostingStrategy> strategies;

    public ValuationServiceImpl(
            InventoryStockSummaryRepository stockSummaryRepository,
            InventoryLedgerEntryRepository ledgerRepository,
            SkuCostStateRepository costStateRepository,
            CostingMethodResolver methodResolver,
            AsOfQueryGuard asOfQueryGuard,
            List<CostingStrategy> costingStrategies,
            @Value("${pos.inventory.valuation.as-of-sku-cap:1000}") int asOfSkuCap) {
        this.stockSummaryRepository = stockSummaryRepository;
        this.ledgerRepository = ledgerRepository;
        this.costStateRepository = costStateRepository;
        this.methodResolver = methodResolver;
        this.asOfQueryGuard = asOfQueryGuard;
        this.asOfSkuCap = asOfSkuCap;
        this.strategies = new EnumMap<>(CostingMethod.class);
        for (CostingStrategy strategy : costingStrategies) {
            this.strategies.put(strategy.method(), strategy);
        }
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public ValuationReportResponse getValuation(@Nullable UUID locationId, @Nullable String sku) {
        List<SkuQty> onHands = currentOnHands(locationId, sku);
        Set<String> stockItemIds = stockItemIds(onHands);
        Map<String, CostingMethod> methods = methodResolver.resolveAll(stockItemIds);
        Map<String, SkuCostState> costStates = findCostStatesByStockItemId(stockItemIds);
        List<ValuationRow> rows = new ArrayList<>(onHands.size());
        for (SkuQty item : onHands) {
            CostingMethod method = methods.get(item.stockItemId());
            BigDecimal unitCost = currentUnitCost(costStates.get(item.stockItemId()), method);
            rows.add(buildRow(item.stockItemId(), item.onHand(), method, unitCost));
        }
        return report(locationId, null, rows);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public ValuationReportResponse getValuationAsOf(
            @Nullable UUID locationId, @Nullable String sku, @NonNull Instant asOf) {
        asOfQueryGuard.check(asOf);

        Set<InventoryLedgerEventType> onHandTypes = InventoryLedgerEventType.onHandAffectingTypes();
        List<SkuQty> onHands = asOfOnHands(locationId, sku, onHandTypes, asOf);
        enforceFullCatalogAsOfCap(locationId, sku, onHands.size());
        Set<String> stockItemIds = stockItemIds(onHands);
        Map<String, CostingMethod> methods = methodResolver.resolveAll(stockItemIds);
        Map<String, SkuCostState> costStates = findCostStatesByStockItemId(stockItemIds);
        Map<String, List<InventoryLedgerEntry>> entriesBySku =
                asOfEntriesByStockItemId(stockItemIds, onHandTypes, asOf);
        List<ValuationRow> rows = new ArrayList<>(onHands.size());
        for (SkuQty item : onHands) {
            CostingMethod method = methods.get(item.stockItemId());
            BigDecimal standardCost = costStates.containsKey(item.stockItemId())
                    ? costStates.get(item.stockItemId()).getStandardCost()
                    : null;
            BigDecimal unitCost = unitCostAsOf(
                    item.stockItemId(), method, standardCost, entriesBySku.getOrDefault(item.stockItemId(), List.of()));
            rows.add(buildRow(item.stockItemId(), item.onHand(), method, unitCost));
        }
        return report(locationId, asOf, rows);
    }

    // ─── On-hand sourcing ────────────────────────────────────────────────────

    private List<SkuQty> currentOnHands(@Nullable UUID locationId, @Nullable String sku) {
        if (sku != null && !sku.isBlank()) {
            long onHand = locationId == null
                    ? stockSummaryRepository.sumOnHandForSku(sku)
                    : stockSummaryRepository
                            .findByStockItemIdAndLocationId(sku, locationId)
                            .map(s -> s.getOnHand())
                            .orElse(0L);
            return onHand == 0L ? List.of() : List.of(new SkuQty(sku, onHand));
        }
        List<InventoryStockSummaryRepository.SkuOnHand> rows = locationId == null
                ? stockSummaryRepository.sumOnHandBySku()
                : stockSummaryRepository.sumOnHandBySkuAtLocation(locationId);
        return rows.stream()
                .map(r -> new SkuQty(r.getStockItemId(), r.getOnHand()))
                .toList();
    }

    private List<SkuQty> asOfOnHands(
            @Nullable UUID locationId, @Nullable String sku, Set<InventoryLedgerEventType> onHandTypes, Instant asOf) {
        if (sku != null && !sku.isBlank()) {
            Integer onHand = locationId == null
                    ? sumChangeForSkuAsOf(sku, onHandTypes, asOf)
                    : ledgerRepository.calculateOnHandForStockItemAtLocationAsOf(sku, locationId, onHandTypes, asOf);
            long value = onHand == null ? 0L : onHand.longValue();
            return value == 0L ? List.of() : List.of(new SkuQty(sku, value));
        }
        List<InventoryLedgerEntryRepository.LocationOnHand> rows = locationId == null
                ? ledgerRepository.sumOnHandBySkuAsOf(onHandTypes, asOf)
                : ledgerRepository.findPositiveOnHandByLocationAsOf(locationId, onHandTypes, asOf);
        return rows.stream()
                .map(r -> new SkuQty(r.getStockItemId(), r.getOnHandQuantity()))
                .toList();
    }

    private @Nullable Integer sumChangeForSkuAsOf(String sku, Set<InventoryLedgerEventType> onHandTypes, Instant asOf) {
        // Reuse the ordered replay stream to derive on-hand for a single SKU across all sites.
        int total = 0;
        for (InventoryLedgerEntry entry :
                ledgerRepository
                        .findByStockItemIdAndEventTypeInAndTimestampLessThanEqualOrderByTimestampAscLedgerEntryIdAsc(
                                sku, onHandTypes, asOf)) {
            total += entry.getChangeInQuantity() == null ? 0 : entry.getChangeInQuantity();
        }
        return total;
    }

    // ─── Unit cost derivation ────────────────────────────────────────────────

    private @Nullable BigDecimal currentUnitCost(@Nullable SkuCostState state, CostingMethod method) {
        if (state == null) {
            return null;
        }
        return deriveUnitCost(state.getAvgCost(), state.getStandardCost(), method);
    }

    private @Nullable BigDecimal unitCostAsOf(
            String sku, CostingMethod method, @Nullable BigDecimal standardCost, List<InventoryLedgerEntry> entries) {
        CostingStrategy strategy = strategy(method);
        CostingStrategy.CostState state = new CostingStrategy.CostState(null, 0L, standardCost);
        for (InventoryLedgerEntry entry : entries) {
            int change = entry.getChangeInQuantity() == null ? 0 : entry.getChangeInQuantity();
            CostingStrategy.CostingInput input =
                    new CostingStrategy.CostingInput(sku, entry.getEventType(), change, entry.getUnitCost(), state);
            state = strategy.cost(input).state();
        }
        return deriveUnitCost(state.avgCost(), state.standardCost(), method);
    }

    private static Set<String> stockItemIds(List<SkuQty> onHands) {
        Set<String> stockItemIds = new HashSet<>(onHands.size());
        for (SkuQty onHand : onHands) {
            stockItemIds.add(onHand.stockItemId());
        }
        return stockItemIds;
    }

    private void enforceFullCatalogAsOfCap(@Nullable UUID locationId, @Nullable String sku, int skuCount) {
        if (isFullCatalogAsOf(locationId, sku) && skuCount > asOfSkuCap) {
            throw new ValuationAsOfSkuCapExceededException(skuCount, asOfSkuCap);
        }
    }

    private static boolean isFullCatalogAsOf(@Nullable UUID locationId, @Nullable String sku) {
        return locationId == null && (sku == null || sku.isBlank());
    }

    private Map<String, SkuCostState> findCostStatesByStockItemId(Set<String> stockItemIds) {
        if (stockItemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SkuCostState> costStates = new HashMap<>(stockItemIds.size());
        for (SkuCostState state : costStateRepository.findByStockItemIdIn(stockItemIds)) {
            costStates.put(state.getStockItemId(), state);
        }
        return Map.copyOf(costStates);
    }

    private Map<String, List<InventoryLedgerEntry>> asOfEntriesByStockItemId(
            Set<String> stockItemIds, Set<InventoryLedgerEventType> onHandTypes, Instant asOf) {
        if (stockItemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<InventoryLedgerEntry>> entriesBySku = new HashMap<>(stockItemIds.size());
        for (InventoryLedgerEntry entry : ledgerRepository
                .findByStockItemIdInAndEventTypeInAndTimestampLessThanEqualOrderByStockItemIdAscTimestampAscLedgerEntryIdAsc(
                        stockItemIds, onHandTypes, asOf)) {
            entriesBySku
                    .computeIfAbsent(entry.getStockItemId(), ignored -> new ArrayList<>())
                    .add(entry);
        }
        return Map.copyOf(entriesBySku);
    }

    /**
     * Current unit cost per method from a (avgCost, standardCost) pair: AVERAGE → running average;
     * STANDARD → standard price, falling back to the latest-receipt-cost memo held in avgCost.
     * Rounded HALF_UP to the ledger unit-cost scale (4).
     */
    private static @Nullable BigDecimal deriveUnitCost(
            @Nullable BigDecimal avgCost, @Nullable BigDecimal standardCost, CostingMethod method) {
        BigDecimal raw = method == CostingMethod.STANDARD ? (standardCost != null ? standardCost : avgCost) : avgCost;
        return raw == null ? null : raw.setScale(VALUE_SCALE, RoundingMode.HALF_UP);
    }

    private CostingStrategy strategy(CostingMethod method) {
        CostingStrategy strategy = strategies.get(method);
        if (strategy == null) {
            throw new IllegalStateException("No costing strategy registered for method " + method);
        }
        return strategy;
    }

    // ─── Assembly ────────────────────────────────────────────────────────────

    private static ValuationRow buildRow(String sku, long onHand, CostingMethod method, @Nullable BigDecimal unitCost) {
        boolean uncosted = unitCost == null;
        BigDecimal value = uncosted
                ? BigDecimal.ZERO.setScale(VALUE_SCALE, RoundingMode.HALF_UP)
                : unitCost.multiply(BigDecimal.valueOf(onHand)).setScale(VALUE_SCALE, RoundingMode.HALF_UP);
        return ValuationRow.builder()
                .stockItemId(sku)
                .onHand(onHand)
                .costingMethod(method.name())
                .unitCostCurrent(unitCost)
                .onHandValue(value)
                .uncosted(uncosted)
                .build();
    }

    private static ValuationReportResponse report(
            @Nullable UUID locationId, @Nullable Instant asOf, List<ValuationRow> rows) {
        BigDecimal total = rows.stream()
                .map(ValuationRow::getOnHandValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(VALUE_SCALE, RoundingMode.HALF_UP);
        return ValuationReportResponse.builder()
                .locationId(locationId)
                .asOf(asOf)
                .rows(rows)
                .totalOnHandValue(total)
                .build();
    }

    private record SkuQty(String stockItemId, long onHand) {}
}
