package com.positivity.inventory.internal.service;

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
import com.positivity.inventory.service.SkuCategoryCutoverService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the SKU_CATEGORY cut-over impact report (#1535).
 *
 * <p><strong>This class deliberately has no {@link SkuCategoryProvider}
 * dependency.</strong> That is the whole reason the report works with
 * {@code pos.inventory.sku-category.resolve-from-replica} off: the flag gates
 * that SPI, so anything routed through it would answer "nothing resolves" and
 * the report would be uniformly, uselessly empty at exactly the moment it is
 * needed. Instead it reads the {@code ext_product} replica directly, which
 * {@link ReplicaSkuCategoryLookup} and the replica table are populated for
 * regardless of the flag.
 *
 * <p>{@link CostingMethodResolver} is injected for {@code defaultMethod()}
 * only — the deployment default — and never to resolve a SKU, since resolving
 * one would go through the gated SPI.
 *
 * <p>Two exclusions keep the report readable. A SKU with an active SKU-scoped
 * costing row is shielded: SKU outranks SKU_CATEGORY both today and after the
 * flip, so nothing about it changes. And a SKU whose projected method already
 * equals its current method is a no-op; it stays out of {@code impactedSkus}
 * while remaining inside {@code evaluatedSkuCount}.
 */
@Service
public class SkuCategoryCutoverServiceImpl implements SkuCategoryCutoverService {

    private final CostingMethodConfigRepository configRepository;
    private final SourcingStrategyConfigRepository sourcingStrategyConfigRepository;
    private final ExtProductReplicaRepository extProductReplicaRepository;
    private final SkuCostStateRepository skuCostStateRepository;
    private final CostingMethodResolver costingMethodResolver;
    private final boolean resolveFromReplicaEnabled;

    public SkuCategoryCutoverServiceImpl(
            CostingMethodConfigRepository configRepository,
            SourcingStrategyConfigRepository sourcingStrategyConfigRepository,
            ExtProductReplicaRepository extProductReplicaRepository,
            SkuCostStateRepository skuCostStateRepository,
            CostingMethodResolver costingMethodResolver,
            @Value("${pos.inventory.sku-category.resolve-from-replica:false}") boolean resolveFromReplicaEnabled) {
        this.configRepository = configRepository;
        this.sourcingStrategyConfigRepository = sourcingStrategyConfigRepository;
        this.extProductReplicaRepository = extProductReplicaRepository;
        this.skuCostStateRepository = skuCostStateRepository;
        this.costingMethodResolver = costingMethodResolver;
        this.resolveFromReplicaEnabled = resolveFromReplicaEnabled;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull SkuCategoryImpactResponse impact() {
        List<CostingMethodConfig> configs =
                configRepository.findByScopeTypeAndActiveTrue(CostingScopeType.SKU_CATEGORY);

        CostingMethod deploymentDefaultMethod = costingMethodResolver.defaultMethod();
        CostingMethod activeDefaultConfigMethod = configRepository
                .findByScopeTypeAndScopeValueIsNullAndActiveTrue(CostingScopeType.DEFAULT)
                .map(CostingMethodConfig::getMethod)
                .orElse(null);
        // The same fallback every unshielded SKU takes today, in the same order the resolver takes it.
        CostingMethod currentMethod =
                activeDefaultConfigMethod != null ? activeDefaultConfigMethod : deploymentDefaultMethod;

        SkuCategoryImpactResponse.SkuCategoryImpactResponseBuilder report = SkuCategoryImpactResponse.builder()
                .resolveFromReplicaEnabled(resolveFromReplicaEnabled)
                .deploymentDefaultMethod(deploymentDefaultMethod)
                .activeDefaultConfigMethod(activeDefaultConfigMethod)
                .activeSkuCategoryConfigCount(configs.size());

        if (configs.isEmpty()) {
            return report.categoriesWithNoReplicatedProducts(List.of())
                    .evaluatedSkuCount(0)
                    .impactedSkuCount(0)
                    .impactedSkuWithCostStateCount(0)
                    .impactedSkus(List.of())
                    .impactedSourcingSkus(sourcingImpact())
                    .build();
        }

        Map<String, CostingMethodConfig> configByName = new LinkedHashMap<>(configs.size());
        for (CostingMethodConfig config : configs) {
            String categoryName = trimToNull(config.getScopeValue());
            if (categoryName != null) {
                configByName.putIfAbsent(categoryName, config);
            }
        }

        List<ExtProductReplica> replicas = replicasForCategories(configByName.keySet());

        Set<String> replicatedNames = new HashSet<>(replicas.size());
        for (ExtProductReplica replica : replicas) {
            String categoryName = trimToNull(replica.getCategoryName());
            if (categoryName != null) {
                replicatedNames.add(categoryName);
            }
        }
        List<String> categoriesWithNoReplicatedProducts = configByName.keySet().stream()
                .filter(name -> !replicatedNames.contains(name))
                .sorted()
                .toList();

        Set<String> shielded = shieldedStockItemIds(replicas);

        List<SkuCategoryImpactRow> impactedSkus = new ArrayList<>();
        for (ExtProductReplica replica : replicas) {
            String stockItemId = replica.getProductId().toString();
            if (shielded.contains(stockItemId)) {
                continue;
            }
            String categoryName = trimToNull(replica.getCategoryName());
            CostingMethodConfig config = categoryName == null ? null : configByName.get(categoryName);
            if (config == null) {
                continue;
            }
            CostingMethod projectedMethod = config.getMethod();
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
                .evaluatedSkuCount(replicas.size())
                .impactedSkuCount(impactedSkus.size())
                .impactedSkuWithCostStateCount(impactedSkuWithCostStateCount)
                .impactedSkus(List.copyOf(impactedSkus))
                .impactedSourcingSkus(sourcingImpact())
                .build();
    }

    /**
     * The SKUs an active SKU-scoped costing row already decides. They resolve from that
     * higher-precedence row both today and after the flip, so the flag cannot move them.
     */
    private Set<String> shieldedStockItemIds(List<ExtProductReplica> replicas) {
        if (replicas.isEmpty()) {
            return Set.of();
        }
        List<String> stockItemIds = replicas.stream()
                .map(replica -> replica.getProductId().toString())
                .toList();
        return configRepository.findByScopeTypeAndScopeValueInAndActiveTrue(CostingScopeType.SKU, stockItemIds).stream()
                .map(CostingMethodConfig::getScopeValue)
                .collect(java.util.stream.Collectors.toSet());
    }

    private void attachCostState(List<SkuCategoryImpactRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<String> stockItemIds =
                rows.stream().map(SkuCategoryImpactRow::getStockItemId).toList();
        Map<String, SkuCostState> stateBySku = new HashMap<>(stockItemIds.size());
        for (SkuCostState state : skuCostStateRepository.findByStockItemIdIn(stockItemIds)) {
            stateBySku.putIfAbsent(state.getStockItemId(), state);
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

    /**
     * The sourcing half of the same flag. No shielding step and no no-op filter: SKU_CATEGORY is the
     * highest-precedence sourcing scope, so nothing outranks it, and today's effective strategy is not
     * computable here (see {@link SourcingImpactRow}) so there is nothing to compare against.
     */
    private List<SourcingImpactRow> sourcingImpact() {
        List<SourcingStrategyConfig> configs =
                sourcingStrategyConfigRepository.findByScopeTypeAndActiveTrue(SourcingScopeType.SKU_CATEGORY);
        if (configs.isEmpty()) {
            return List.of();
        }

        Map<String, SourcingStrategyConfig> configByName = new LinkedHashMap<>(configs.size());
        for (SourcingStrategyConfig config : configs) {
            String categoryName = trimToNull(config.getScopeValue());
            if (categoryName != null) {
                configByName.putIfAbsent(categoryName, config);
            }
        }

        List<SourcingImpactRow> rows = new ArrayList<>();
        for (ExtProductReplica replica : replicasForCategories(configByName.keySet())) {
            String categoryName = trimToNull(replica.getCategoryName());
            SourcingStrategyConfig config = categoryName == null ? null : configByName.get(categoryName);
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

    private List<ExtProductReplica> replicasForCategories(Set<String> categoryNames) {
        if (categoryNames.isEmpty()) {
            return List.of();
        }
        return extProductReplicaRepository.findByTrimmedCategoryNameIn(Set.copyOf(categoryNames));
    }

    /**
     * Blank and absent are the same statement from catalog, exactly as
     * {@link ReplicaSkuCategoryLookup} treats them — the report must match on the same
     * normalisation the runtime path would.
     */
    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
