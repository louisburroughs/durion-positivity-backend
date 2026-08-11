package com.positivity.supplier.internal.spi;

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
 * <p><strong>Implementations own redaction.</strong> {@link ExchangeContext#requestBody()} and
 * {@link ExchangeContext#responseBody()} are raw wire documents; the base client does not redact
 * them and cannot, being format-agnostic. An observer must apply the binding's
 * {@code captureLevel} and any body-field redaction before persisting or logging either
 * (ADR-0050 §7).
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
