package com.positivity.supplier.internal.client;

import org.jspecify.annotations.NonNull;

/**
 * Observer of every outbound supplier attempt. The seam that keeps transport concerns out of the
 * base client: slice 3's exchange-audit persistence is an observer, so {@code SupplierBaseClient}
 * never depends on a repository, and the audit trail can be extended without reopening transport
 * code.
 *
 * <p>Invoked <strong>once per attempt, including failures and each individual retry</strong> —
 * ADR-0050 §7 requires failed exchanges to be recorded, so an observer that only saw successes
 * would defeat the audit trail's purpose.
 *
 * <p>Implementations must be non-throwing and reasonably fast: the base client treats observer
 * failure as an observability problem, never as a transport failure, and will not fail a
 * successful vendor exchange because auditing had a bad day. They must also tolerate being
 * called from a scheduler thread with no security context.
 */
public interface ExchangeObserver {

    /**
     * Called once for a completed attempt.
     *
     * @param context the attempt's observable facts; carries no credential material
     */
    void onExchange(@NonNull ExchangeContext context);
}
