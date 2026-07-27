package com.positivity.inventory.internal.service;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Internal seam the ledger posting funnel calls after an inbound on-hand posting to attempt
 * availability-driven backorder resolution (odoo-parity G1, issue #1046).
 *
 * <p>Kept as a module-internal interface (not part of the public {@code service} surface) and
 * consumed by {@code LedgerPostingServiceImpl} through an {@code ObjectProvider} so the funnel does
 * not form an eager dependency cycle with {@code BackorderServiceImpl} (which itself posts through
 * the funnel). The trigger only ever posts the ATP-neutral {@code BACKORDER_RESOLVED} entry, which
 * is not on-hand-affecting and therefore cannot re-enter this trigger — the loop is closed by
 * construction.
 */
public interface BackorderResolutionTrigger {

    /**
     * Attempt to resolve OPEN backorders for one SKU at one site after availability increased there.
     * Runs in the caller's transaction, after the summary delta has been applied. Best-effort per
     * backorder: a failure to resolve one candidate must not roll back the inbound posting.
     *
     * @param sku stock-item identifier whose availability increased (ledger stock-item string)
     * @param locationId site whose availability increased; null keys are ignored
     */
    void onInboundAvailability(@NonNull String sku, @Nullable UUID locationId);
}
