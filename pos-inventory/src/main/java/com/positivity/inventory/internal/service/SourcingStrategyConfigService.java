package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.sourcing.SourcingStrategyConfigRequest;
import com.positivity.inventory.internal.dto.sourcing.SourcingStrategyConfigResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Admin operations on sourcing-strategy configuration (odoo-parity H1, issue
 * #1037): the per-scope rows resolved by the internal sourcing strategy
 * engine (SKU_CATEGORY → SITE → DEFAULT → platform default FIFO).
 */
public interface SourcingStrategyConfigService {

    /** All configuration rows (active and inactive), ordered by scope. */
    @NonNull
    List<SourcingStrategyConfigResponse> listConfigs();

    /**
     * Creates or updates the configuration row for the request's scope and
     * reactivates it. At most one row exists per (scopeType, scopeValue).
     */
    @NonNull
    SourcingStrategyConfigResponse upsertConfig(@NonNull SourcingStrategyConfigRequest request);

    /** Soft delete: deactivates the row so it stops participating in resolution. */
    @NonNull
    SourcingStrategyConfigResponse deactivateConfig(@NonNull UUID configId);
}
