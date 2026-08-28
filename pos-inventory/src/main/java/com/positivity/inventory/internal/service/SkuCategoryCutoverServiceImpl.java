package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.config.SkuCategoryCutoverService;
import com.positivity.inventory.internal.dto.costing.SkuCategoryImpactResponse;
import com.positivity.inventory.internal.dto.costing.SkuCategoryImpactRow;
import com.positivity.inventory.internal.dto.costing.SourcingImpactRow;
import com.positivity.inventory.internal.entity.CostingMethodConfig;
import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.SkuCostState;
import com.positivity.inventory.internal.entity.SourcingStrategyConfig;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.CostingScopeType;
import com.positivity.inventory.internal.enums.SourcingScopeType;
import com.positivity.inventory.internal.repository.CostingMethodConfigRepository;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import com.positivity.inventory.internal.repository.SourcingStrategyConfigRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the SKU_CATEGORY cut-over impact report (#1535).
 *
 * <p><strong>No {@link SkuCategoryProvider} dependency, by design.</strong> That
 * is why the report works with {@code pos.inventory.sku-category.resolve-from-replica}
 * off: the flag gates that SPI, so anything routed through it would answer
 * "nothing resolves" and the report would be uniformly, uselessly empty at
 * exactly the moment it is needed. It reads the {@code ext_product} replica
 * directly, which is populated regardless of the flag.
 * {@link CostingMethodResolver} is injected for {@code defaultMethod()} only —
 * never to resolve a SKU, since resolving one routes through the gated SPI.
 *
 * <p><strong>The flag is an input, not a caption.</strong> {@code currentMethod}
 * is decided per row: with the flag off a matched unshielded SKU currently takes
 * the global fallback, so a differing category method is real pending impact;
 * with the flag on it already resolves from its category, so current equals
 * projected and the existing no-op filter drops it. Without this the report
 * returned the same number before and after the flip and could never confirm
 * its own cut-over.
 *
 * <p><strong>Matching mirrors the runtime exactly</strong>, including where the
 * runtime is unforgiving: {@link CostingMethodResolver} and
 * {@link SourcingStrategyServiceImpl} both key on the stored {@code scopeValue}
 * <em>verbatim</em> while the category side arrives trimmed, so a row stored as
 * {@code "  Beverages  "} can never fire. This class must not quietly trim such
 * a row into working — being right about hand-authored config is the report's
 * whole job — so those rows are excluded from matching and surfaced separately
 * as {@code categoriesWithUntrimmedScopeValue}. Duplicate scope values resolve
 * last-wins, as the resolver's map population does.
 *
 * <p><strong>Everything unbounded is bounded.</strong> The result is sized by
 * however many products carry a configured category name, which no operator
 * controls, and this runs on every boot. The product scan is capped at
 * {@code pos.inventory.sku-category.impact-sku-cap} (reporting {@code truncated}
 * rather than silently shortening a report used to decide a financial
 * cut-over), the two category feeds share one scan, and every {@code IN} list is
 * chunked so the PostgreSQL bind-parameter ceiling is structurally unreachable.
 */
@Service
public class SkuCategoryCutoverServiceImpl implements SkuCategoryCutoverService {

    /**
     * Well under PostgreSQL's ~65534 bind-parameter ceiling, and applied regardless of the cap so
     * that raising the cap can never turn a slow report into a hard query failure.
     */
    private static final int IN_CLAUSE_CHUNK_SIZE = 1000;

    private final CostingMethodConfigRepository configRepository;
    private final SourcingStrategyConfigRepository sourcingStrategyConfigRepository;
    private final ExtProductReplicaRepository extProductReplicaRepository;
    private final SkuCostStateRepository skuCostStateRepository;
    private final CostingMethodResolver costingMethodResolver;
    private final boolean resolveFromReplicaEnabled;
    private final int impactSkuCap;

    public SkuCategoryCutoverServiceImpl(
            CostingMethodConfigRepository configRepository,
            SourcingStrategyConfigRepository sourcingStrategyConfigRepository,
            ExtProductReplicaRepository extProductReplicaRepository,
            SkuCostStateRepository skuCostStateRepository,
            CostingMethodResolver costingMethodResolver,
            @Value("${pos.inventory.sku-category.resolve-from-replica:false}") boolean resolveFromReplicaEnabled,
            @Value("${pos.inventory.sku-category.impact-sku-cap:5000}") int impactSkuCap) {
        this.configRepository = configRepository;
        this.sourcingStrategyConfigRepository = sourcingStrategyConfigRepository;
        this.extProductReplicaRepository = extProductReplicaRepository;
        this.skuCostStateRepository = skuCostStateRepository;
        this.costingMethodResolver = costingMethodResolver;
        this.resolveFromReplicaEnabled = resolveFromReplicaEnabled;
        this.impactSkuCap = impactSkuCap;
    }

    @Override
    @Transactional(readOnly = true)
    public long activeSkuCategoryConfigCount() {
        return configRepository.countByScopeTypeAndActiveTrue(CostingScopeType.SKU_CATEGORY);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull SkuCategoryImpactResponse impact() {
        List<CostingMethodConfig> costingConfigs =
                configRepository.findByScopeTypeAndActiveTrue(CostingScopeType.SKU_CATEGORY);
        List<SourcingStrategyConfig> sourcingConfigs =
                sourcingStrategyConfigRepository.findByScopeTypeAndActiveTrue(SourcingScopeType.SKU_CATEGORY);

        ScopeIndex<CostingMethodConfig> costing = index(costingConfigs, CostingMethodConfig::getScopeValue);
        ScopeIndex<SourcingStrategyConfig> sourcing = index(sourcingConfigs, SourcingStrategyConfig::getScopeValue);

        CostingMethod deploymentDefaultMethod = costingMethodResolver.defaultMethod();
        CostingMethod activeDefaultConfigMethod = configRepository
                .findByScopeTypeAndScopeValueIsNullAndActiveTrue(CostingScopeType.DEFAULT)
                .map(CostingMethodConfig::getMethod)
                .orElse(null);
        // The fallback every unshielded SKU takes while the category step is inert, in the resolver's
        // own order: active DEFAULT row, else the deployment default.
        CostingMethod globalFallback =
                activeDefaultConfigMethod != null ? activeDefaultConfigMethod : deploymentDefaultMethod;

        Set<String> untrimmed = new TreeSet<>(costing.untrimmedScopeValues());
        untrimmed.addAll(sourcing.untrimmedScopeValues());

        SkuCategoryImpactResponse.SkuCategoryImpactResponseBuilder report = SkuCategoryImpactResponse.builder()
                .resolveFromReplicaEnabled(resolveFromReplicaEnabled)
                .deploymentDefaultMethod(deploymentDefaultMethod)
                .activeDefaultConfigMethod(activeDefaultConfigMethod)
                .activeSkuCategoryConfigCount(costingConfigs.size())
                .categoriesWithUntrimmedScopeValue(List.copyOf(untrimmed))
                .impactSkuCap(impactSkuCap);

        // One scan for both feeds: findByTrimmedCategoryNameIn is a sequential scan, so running it
        // once per feed doubled the most expensive thing this method does.
        Set<String> scanNames = new HashSet<>(costing.byScopeValue().keySet());
        scanNames.addAll(sourcing.byScopeValue().keySet());
        ReplicaScan scan = scanReplicas(scanNames);

        long evaluatedSkuCount = countReplicas(costing.byScopeValue().keySet());
        Set<String> replicatedNames =
                distinctReplicatedNames(costing.byScopeValue().keySet());
        List<String> categoriesWithNoReplicatedProducts = costing.byScopeValue().keySet().stream()
                .filter(name -> !replicatedNames.contains(name))
                .sorted()
                .toList();

        Set<String> shielded = shieldedStockItemIds(scan.replicas());

        List<SkuCategoryImpactRow> impactedSkus = new ArrayList<>();
        int categoryMatchedSkuCount = 0;
        for (ExtProductReplica replica : scan.replicas()) {
            String stockItemId = replica.getProductId().toString();
            String categoryName = trimToNull(replica.getCategoryName());
            CostingMethodConfig config =
                    categoryName == null ? null : costing.byScopeValue().get(categoryName);
            if (config == null || shielded.contains(stockItemId)) {
                continue;
            }
            categoryMatchedSkuCount++;

            CostingMethod projectedMethod = config.getMethod();
            // With the flag on this SKU already resolves from its category, so its current method IS
            // the projected one and the no-op filter below drops it. With the flag off it still takes
            // the global fallback, so a differing category method is real pending impact.
            CostingMethod currentMethod = resolveFromReplicaEnabled ? projectedMethod : globalFallback;
            if (projectedMethod == currentMethod) {
                continue;
            }
            impactedSkus.add(SkuCategoryImpactRow.builder()
                    .stockItemId(stockItemId)
                    .productId(replica.getProductId())
                    .categoryId(replica.getCategoryId())
                    .categoryName(categoryName)
                    .configId(config.getConfigId())
                    .currentMethod(currentMethod)
                    .projectedMethod(projectedMethod)
                    .hasCostState(false)
                    .build());
        }

        attachCostState(impactedSkus);
        int impactedSkuWithCostStateCount = (int) impactedSkus.stream()
                .filter(SkuCategoryImpactRow::isHasCostState)
                .count();

        return report.categoriesWithNoReplicatedProducts(categoriesWithNoReplicatedProducts)
                .evaluatedSkuCount(evaluatedSkuCount)
                .categoryMatchedSkuCount(categoryMatchedSkuCount)
                .impactedSkuCount(impactedSkus.size())
                .impactedSkuWithCostStateCount(impactedSkuWithCostStateCount)
                .impactedSkus(List.copyOf(impactedSkus))
                .impactedSourcingSkus(sourcingImpact(sourcing, scan.replicas()))
                .truncated(scan.truncated())
                .build();
    }

    /**
     * The sourcing half of the same flag, off the shared scan. No shielding and no no-op filter:
     * SKU_CATEGORY is the highest-precedence sourcing scope so nothing outranks it, and today's
     * effective strategy is not computable here (see {@link SourcingImpactRow}).
     */
    private List<SourcingImpactRow> sourcingImpact(
            ScopeIndex<SourcingStrategyConfig> sourcing, List<ExtProductReplica> replicas) {
        if (sourcing.byScopeValue().isEmpty()) {
            return List.of();
        }
        List<SourcingImpactRow> rows = new ArrayList<>();
        for (ExtProductReplica replica : replicas) {
            String categoryName = trimToNull(replica.getCategoryName());
            SourcingStrategyConfig config =
                    categoryName == null ? null : sourcing.byScopeValue().get(categoryName);
            if (config == null) {
                continue;
            }
            rows.add(SourcingImpactRow.builder()
                    .stockItemId(replica.getProductId().toString())
                    .productId(replica.getProductId())
                    .categoryName(categoryName)
                    .configId(config.getConfigId())
                    .projectedStrategy(config.getStrategy())
                    .build());
        }
        return List.copyOf(rows);
    }

    /** The SKUs an active SKU-scoped costing row already decides; the flag cannot move them. */
    private Set<String> shieldedStockItemIds(List<ExtProductReplica> replicas) {
        if (replicas.isEmpty()) {
            return Set.of();
        }
        List<String> stockItemIds = replicas.stream()
                .map(replica -> replica.getProductId().toString())
                .toList();
        Set<String> shielded = new HashSet<>();
        for (List<String> batch : chunk(stockItemIds)) {
            configRepository
                    .findByScopeTypeAndScopeValueInAndActiveTrue(CostingScopeType.SKU, batch)
                    .forEach(config -> shielded.add(config.getScopeValue()));
        }
        return shielded;
    }

    private void attachCostState(List<SkuCategoryImpactRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<String> stockItemIds =
                rows.stream().map(SkuCategoryImpactRow::getStockItemId).toList();
        Map<String, SkuCostState> stateBySku = new HashMap<>(stockItemIds.size());
        for (List<String> batch : chunk(stockItemIds)) {
            for (SkuCostState state : skuCostStateRepository.findByStockItemIdIn(batch)) {
                stateBySku.putIfAbsent(state.getStockItemId(), state);
            }
        }
        for (SkuCategoryImpactRow row : rows) {
            SkuCostState state = stateBySku.get(row.getStockItemId());
            if (state != null) {
                row.setHasCostState(true);
                row.setOnHandQty(state.getOnHandQty());
                row.setAvgCost(state.getAvgCost());
                row.setStandardCost(state.getStandardCost());
            }
        }
    }

    /** Scans at most {@code impactSkuCap} products, fetching one extra row to detect truncation. */
    private ReplicaScan scanReplicas(Set<String> categoryNames) {
        if (categoryNames.isEmpty()) {
            return new ReplicaScan(List.of(), false);
        }
        int limit = impactSkuCap + 1;
        List<ExtProductReplica> collected = new ArrayList<>();
        for (List<String> batch : chunk(List.copyOf(categoryNames))) {
            if (collected.size() >= limit) {
                break;
            }
            collected.addAll(extProductReplicaRepository.findByTrimmedCategoryNameIn(
                    batch, PageRequest.of(0, limit - collected.size())));
        }
        boolean truncated = collected.size() > impactSkuCap;
        return new ReplicaScan(List.copyOf(truncated ? collected.subList(0, impactSkuCap) : collected), truncated);
    }

    private long countReplicas(Set<String> categoryNames) {
        if (categoryNames.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        // Additive across batches: a product carries one category name, so it matches at most one.
        for (List<String> batch : chunk(List.copyOf(categoryNames))) {
            total += extProductReplicaRepository.countByTrimmedCategoryNameIn(batch);
        }
        return total;
    }

    private Set<String> distinctReplicatedNames(Set<String> categoryNames) {
        if (categoryNames.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (List<String> batch : chunk(List.copyOf(categoryNames))) {
            names.addAll(extProductReplicaRepository.findDistinctTrimmedCategoryNamesIn(batch));
        }
        return names;
    }

    /**
     * Indexes config rows the way the runtime resolver does — verbatim scope value, last duplicate
     * wins — and sets aside the rows whose stored value carries whitespace, which therefore can
     * never equal a trimmed category name and can never fire.
     */
    private static <T> ScopeIndex<T> index(List<T> configs, Function<T, String> scopeValueOf) {
        Map<String, T> byScopeValue = new LinkedHashMap<>(configs.size());
        Set<String> untrimmed = new TreeSet<>();
        for (T config : configs) {
            String scopeValue = scopeValueOf.apply(config);
            if (scopeValue == null) {
                continue;
            }
            if (!scopeValue.equals(scopeValue.trim()) || scopeValue.isEmpty()) {
                untrimmed.add(scopeValue);
                continue;
            }
            byScopeValue.put(scopeValue, config);
        }
        return new ScopeIndex<>(Map.copyOf(byScopeValue), List.copyOf(untrimmed));
    }

    private static <T> List<List<T>> chunk(List<T> items) {
        if (items.size() <= IN_CLAUSE_CHUNK_SIZE) {
            return List.of(items);
        }
        List<List<T>> batches = new ArrayList<>();
        for (int start = 0; start < items.size(); start += IN_CLAUSE_CHUNK_SIZE) {
            batches.add(items.subList(start, Math.min(items.size(), start + IN_CLAUSE_CHUNK_SIZE)));
        }
        return batches;
    }

    /**
     * Blank and absent are the same statement from catalog, exactly as {@link ReplicaSkuCategoryLookup}
     * treats them — the report must normalise the replica side the way the runtime path does.
     */
    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Config rows split into what can match and what never will. */
    private record ScopeIndex<T>(Map<String, T> byScopeValue, List<String> untrimmedScopeValues) {}

    /** A capped page of the product scan, and whether the cap cut it short. */
    private record ReplicaScan(List<ExtProductReplica> replicas, boolean truncated) {}
}
