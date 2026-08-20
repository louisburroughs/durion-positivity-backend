package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.sourcing.SourcingStrategyConfigRequest;
import com.positivity.inventory.internal.dto.sourcing.SourcingStrategyConfigResponse;
import com.positivity.inventory.internal.entity.SourcingStrategyConfig;
import com.positivity.inventory.internal.enums.SourcingScopeType;
import com.positivity.inventory.internal.enums.SourcingStrategy;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.SourcingStrategyConfigRepository;
import com.positivity.inventory.service.SourcingStrategyConfigService;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sourcing strategy engine (odoo-parity H1/H2, issue #1037) plus the admin
 * operations on its {@code sourcing_strategy_config} rows.
 *
 * <p><strong>Resolution precedence</strong> (Odoo {@code _get_removal_strategy}
 * analog): active SKU_CATEGORY config for the SKU's category → active SITE
 * config → active DEFAULT config → platform default {@code FIFO}. The catalog
 * replica carries no category yet, so with the default
 * {@link NoOpSkuCategoryProvider} the category step always falls through
 * (documented; a catalog contract extension lights it up). When the caller
 * passes no site, the engine derives it from the first candidate's
 * {@code ext_storage_location} replica row.
 *
 * <p><strong>Fallbacks</strong> (decision records both configured and
 * effective strategy): FEFO → FIFO while the {@link LotExpiryProvider} has no
 * expiry data for the SKU (lots arrive with odoo-parity E1-E3); PROXIMITY →
 * FIFO when the selection has no reference location to measure distance from.
 */
@Service
public class SourcingStrategyServiceImpl implements SourcingStrategyService, SourcingStrategyConfigService {

    private final SourcingStrategyConfigRepository configRepository;
    private final InventoryStockSummaryRepository stockSummaryRepository;
    private final ExtStorageLocationReplicaRepository extStorageLocationReplicaRepository;
    private final SkuCategoryProvider skuCategoryProvider;
    private final LotExpiryProvider lotExpiryProvider;
    private final Map<SourcingStrategy, SourcingOrderingStrategy> strategies;

    public SourcingStrategyServiceImpl(
            SourcingStrategyConfigRepository configRepository,
            InventoryStockSummaryRepository stockSummaryRepository,
            ExtStorageLocationReplicaRepository extStorageLocationReplicaRepository,
            SkuCategoryProvider skuCategoryProvider,
            LotExpiryProvider lotExpiryProvider,
            List<SourcingOrderingStrategy> orderingStrategies) {
        this.configRepository = configRepository;
        this.stockSummaryRepository = stockSummaryRepository;
        this.extStorageLocationReplicaRepository = extStorageLocationReplicaRepository;
        this.skuCategoryProvider = skuCategoryProvider;
        this.lotExpiryProvider = lotExpiryProvider;
        this.strategies = new EnumMap<>(SourcingStrategy.class);
        for (SourcingOrderingStrategy strategy : orderingStrategies) {
            this.strategies.put(strategy.strategy(), strategy);
        }
    }

    // ─── Engine ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public @NonNull SourcingDecision orderCandidates(@NonNull SourcingSelection selection) {
        SourcingStrategy configured = resolveStrategy(selection);
        SourcingStrategy effective = effectiveStrategy(configured, selection);

        SourcingOrderingStrategy ordering = strategies.get(effective);
        if (ordering == null) {
            throw new IllegalStateException("No ordering registered for sourcing strategy " + effective);
        }
        return new SourcingDecision(configured, effective, ordering.order(selection));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<SourceSelection> selectSource(
            @NonNull SourcingSelection selection, @NonNull BigDecimal neededQuantity) {
        SourcingDecision decision = orderCandidates(selection);
        return decision.orderedCandidates().stream()
                .filter(candidate -> Quantities.gte(availableOf(selection.stockItemId(), candidate), neededQuantity))
                .findFirst()
                .map(candidate -> new SourceSelection(candidate, decision));
    }

    /**
     * SKU_CATEGORY → SITE → DEFAULT → FIFO. Category-scoped rows are skipped
     * whenever the SKU's category is unresolvable (the current default —
     * {@code ext_product} has no category field).
     */
    private SourcingStrategy resolveStrategy(SourcingSelection selection) {
        Optional<SourcingStrategy> byCategory = skuCategoryProvider
                .categoryOf(selection.stockItemId())
                .flatMap(category -> configRepository.findByScopeTypeAndScopeValueAndActiveTrue(
                        SourcingScopeType.SKU_CATEGORY, category))
                .map(SourcingStrategyConfig::getStrategy);
        if (byCategory.isPresent()) {
            return byCategory.get();
        }

        UUID siteId = selection.siteId() != null ? selection.siteId() : deriveSiteId(selection);
        if (siteId != null) {
            Optional<SourcingStrategy> bySite = configRepository
                    .findByScopeTypeAndScopeValueAndActiveTrue(SourcingScopeType.SITE, siteId.toString())
                    .map(SourcingStrategyConfig::getStrategy);
            if (bySite.isPresent()) {
                return bySite.get();
            }
        }

        return configRepository
                .findByScopeTypeAndScopeValueIsNullAndActiveTrue(SourcingScopeType.DEFAULT)
                .map(SourcingStrategyConfig::getStrategy)
                .orElse(SourcingStrategy.FIFO);
    }

    private SourcingStrategy effectiveStrategy(SourcingStrategy configured, SourcingSelection selection) {
        if (configured == SourcingStrategy.FEFO && !lotExpiryProvider.hasExpiryData(selection.stockItemId())) {
            // Lots do not exist yet (odoo-parity E1); FEFO activates once a
            // lot-aware LotExpiryProvider reports expiry data for the SKU.
            return SourcingStrategy.FIFO;
        }
        if (configured == SourcingStrategy.PROXIMITY && selection.referenceLocationId() == null) {
            return SourcingStrategy.FIFO;
        }
        return configured;
    }

    /** Site of the first candidate whose storage-location replica is known, else null. */
    private @Nullable UUID deriveSiteId(SourcingSelection selection) {
        for (SourcingCandidate candidate : selection.candidates()) {
            Optional<UUID> siteId = extStorageLocationReplicaRepository
                    .findById(candidate.locationId())
                    .map(replica -> replica.getSiteId());
            if (siteId.isPresent()) {
                return siteId.get();
            }
        }
        return null;
    }

    private BigDecimal availableOf(String stockItemId, SourcingCandidate candidate) {
        if (candidate.availableQuantity() != null) {
            return candidate.availableQuantity();
        }
        return stockSummaryRepository
                .findByStockItemIdAndLocationId(stockItemId, candidate.locationId())
                .map(summary -> Quantities.nz(summary.getOnHand()).subtract(Quantities.nz(summary.getAllocated())))
                .orElse(BigDecimal.ZERO);
    }

    // ─── Admin (SourcingStrategyConfigService) ───────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<SourcingStrategyConfigResponse> listConfigs() {
        return configRepository.findAllByOrderByScopeTypeAscScopeValueAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public @NonNull SourcingStrategyConfigResponse upsertConfig(@NonNull SourcingStrategyConfigRequest request) {
        SourcingScopeType scopeType = request.getScopeType();
        String scopeValue = normalizeScopeValue(scopeType, request.getScopeValue());

        SourcingStrategyConfig config = (scopeValue == null
                        ? configRepository.findByScopeTypeAndScopeValueIsNull(scopeType)
                        : configRepository.findByScopeTypeAndScopeValue(scopeType, scopeValue))
                .orElseGet(() -> SourcingStrategyConfig.builder()
                        .scopeType(scopeType)
                        .scopeValue(scopeValue)
                        .build());
        config.setStrategy(request.getStrategy());
        config.setActive(true);
        return toResponse(configRepository.save(config));
    }

    @Override
    @Transactional
    public @NonNull SourcingStrategyConfigResponse deactivateConfig(@NonNull UUID configId) {
        SourcingStrategyConfig config = configRepository
                .findById(configId)
                .orElseThrow(() -> new ResourceNotFoundException("Sourcing strategy config", configId.toString()));
        config.setActive(false);
        return toResponse(configRepository.save(config));
    }

    private String normalizeScopeValue(SourcingScopeType scopeType, String scopeValue) {
        String trimmed = scopeValue == null || scopeValue.isBlank() ? null : scopeValue.trim();
        switch (scopeType) {
            case DEFAULT -> {
                if (trimmed != null) {
                    throw new IllegalArgumentException("scopeValue must be omitted for DEFAULT scope");
                }
            }
            case SITE -> {
                if (trimmed == null) {
                    throw new IllegalArgumentException("scopeValue (site UUID) is required for SITE scope");
                }
                try {
                    trimmed = UUID.fromString(trimmed).toString();
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("scopeValue must be a site UUID for SITE scope");
                }
            }
            case SKU_CATEGORY -> {
                if (trimmed == null) {
                    throw new IllegalArgumentException("scopeValue (category) is required for SKU_CATEGORY scope");
                }
            }
        }
        return trimmed;
    }

    private SourcingStrategyConfigResponse toResponse(SourcingStrategyConfig config) {
        return SourcingStrategyConfigResponse.builder()
                .configId(config.getConfigId())
                .scopeType(config.getScopeType())
                .scopeValue(config.getScopeValue())
                .strategy(config.getStrategy())
                .active(config.isActive())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}
