package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.costing.CostingMethodConfigRequest;
import com.positivity.inventory.internal.dto.costing.CostingMethodConfigResponse;
import java.util.List;
import java.util.UUID;
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

    /**
     * Deactivates one configuration row so it stops participating in resolution, recording a
     * {@code DEACTIVATED} row in the change log (#1535). This is a soft delete; the row is never
     * removed, and deactivating an already inactive row returns it without writing a second log
     * row — an append-only audit must not be pollutable by a repeated DELETE.
     *
     * @throws com.positivity.inventory.internal.exception.ResourceNotFoundException when no
     *     configuration exists for {@code configId}
     */
    @NonNull
    CostingMethodConfigResponse deactivateConfig(@NonNull UUID configId);
}
