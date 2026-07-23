package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.SourcingStrategy;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingCandidate;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingSelection;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * SPI for one registered {@link SourcingStrategy} ordering (odoo-parity H1,
 * issue #1037). Implementations are Spring beans collected by
 * {@code SourcingStrategyServiceImpl}; each must be deterministic and use
 * ascending location id as the final tie-breaker.
 */
public interface SourcingOrderingStrategy {

    /** The strategy this bean implements. */
    @NonNull
    SourcingStrategy strategy();

    /** Orders the selection's candidates per this strategy, best first. */
    @NonNull
    List<SourcingCandidate> order(@NonNull SourcingSelection selection);
}
