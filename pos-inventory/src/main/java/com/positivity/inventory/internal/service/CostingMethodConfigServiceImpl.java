package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.costing.CostingMethodConfigRequest;
import com.positivity.inventory.internal.dto.costing.CostingMethodConfigResponse;
import com.positivity.inventory.internal.entity.CostMethodChangeLog;
import com.positivity.inventory.internal.entity.CostingMethodConfig;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.CostingScopeType;
import com.positivity.inventory.internal.repository.CostMethodChangeLogRepository;
import com.positivity.inventory.internal.repository.CostingMethodConfigRepository;
import com.positivity.inventory.service.CostingMethodConfigService;
import com.positivity.security.common.SecurityContextHelper;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin operations on {@code sku_cost_method_config} rows (odoo-parity J1,
 * issue #1048; ADR-0048). Mirrors {@code SourcingStrategyServiceImpl}'s admin
 * surface (H1) and additionally records a who/when/from/to row in the
 * {@code cost_method_change_log} whenever a scope's effective method changes.
 *
 * <p>J1 applies the resolved method <strong>going forward</strong> only.
 * Restating opening values on a method switch (the revaluation cut-over) is the
 * J4 revaluation workflow — deliberately not performed here.
 */
@Service
public class CostingMethodConfigServiceImpl implements CostingMethodConfigService {

    private final CostingMethodConfigRepository configRepository;
    private final CostMethodChangeLogRepository changeLogRepository;

    public CostingMethodConfigServiceImpl(
            CostingMethodConfigRepository configRepository, CostMethodChangeLogRepository changeLogRepository) {
        this.configRepository = configRepository;
        this.changeLogRepository = changeLogRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<CostingMethodConfigResponse> listConfigs() {
        return configRepository.findAllByOrderByScopeTypeAscScopeValueAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public @NonNull CostingMethodConfigResponse upsertConfig(@NonNull CostingMethodConfigRequest request) {
        CostingScopeType scopeType = request.getScopeType();
        String scopeValue = normalizeScopeValue(scopeType, request.getScopeValue());

        CostingMethodConfig config = (scopeValue == null
                        ? configRepository.findByScopeTypeAndScopeValueIsNull(scopeType)
                        : configRepository.findByScopeTypeAndScopeValue(scopeType, scopeValue))
                .orElseGet(() -> CostingMethodConfig.builder()
                        .scopeType(scopeType)
                        .scopeValue(scopeValue)
                        .build());

        CostingMethod fromMethod = config.getMethod();
        CostingMethod toMethod = request.getMethod();
        boolean methodChanged = fromMethod == null || fromMethod != toMethod;

        config.setMethod(toMethod);
        config.setActive(true);
        CostingMethodConfig saved = configRepository.save(config);

        if (methodChanged) {
            changeLogRepository.save(CostMethodChangeLog.builder()
                    .scopeType(scopeType)
                    .scopeValue(scopeValue)
                    .fromMethod(fromMethod)
                    .toMethod(toMethod)
                    .changedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .build());
        }

        return toResponse(saved);
    }

    private String normalizeScopeValue(CostingScopeType scopeType, @Nullable String scopeValue) {
        String trimmed = scopeValue == null || scopeValue.isBlank() ? null : scopeValue.trim();
        switch (scopeType) {
            case DEFAULT -> {
                if (trimmed != null) {
                    throw new IllegalArgumentException("scopeValue must be omitted for DEFAULT scope");
                }
            }
            case SKU -> {
                if (trimmed == null) {
                    throw new IllegalArgumentException("scopeValue (stock item id) is required for SKU scope");
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

    private CostingMethodConfigResponse toResponse(CostingMethodConfig config) {
        return CostingMethodConfigResponse.builder()
                .configId(config.getConfigId())
                .scopeType(config.getScopeType())
                .scopeValue(config.getScopeValue())
                .method(config.getMethod())
                .active(config.isActive())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}
