package com.positivity.supplier.service.model;

/**
 * Retry backoff strategy of a vendor profile's protocol defaults (ADR-0050 §2, architecture doc §7
 * {@code protocolDefaults.retry.backoff}). {@code null} on a profile means "use the deployment
 * default".
 *
 * <p>Contract-side mirror of the internal enum of the same name, following the
 * {@link PayloadCaptureLevel} precedent: no internal type may cross the service boundary
 * (ADR-0026), so the two are kept in deliberate lockstep and pinned by
 * {@code SupplierContractKeyParityTest}.
 */
public enum RetryBackoff {
    /** Constant delay between retry attempts. */
    FIXED,
    /** Exponentially growing delay between retry attempts. */
    EXPONENTIAL
}
