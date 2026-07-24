package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.costing.CostingMethodConfigRequest;
import com.positivity.inventory.internal.dto.costing.CostingMethodConfigResponse;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Admin operations on costing-method configuration (odoo-parity J1, issue
 * #1048; ADR-0048): the per-scope rows resolved by the internal costing engine
 * (SKU → SKU_CATEGORY → DEFAULT → deployment default
 * {@code pos.inventory.valuation.default-method}). Every upsert that changes a
 * scope's method records a who/when/from/to row in the change log.
 */
public interface CostingMethodConfigService {

    /** All configuration rows (active and inactive), ordered by scope. */
    @NonNull
    List<CostingMethodConfigResponse> listConfigs();

    /**
     * Creates or updates the configuration row for the request's scope and
     * reactivates it, recording a change-log row when the effective method
     * changes. At most one row exists per (scopeType, scopeValue).
     */
    @NonNull
    CostingMethodConfigResponse upsertConfig(@NonNull CostingMethodConfigRequest request);
}
