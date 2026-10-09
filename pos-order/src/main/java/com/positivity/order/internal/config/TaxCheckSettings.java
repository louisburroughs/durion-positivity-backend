package com.positivity.order.internal.config;

import java.time.Duration;
import org.jspecify.annotations.NonNull;

/**
 * Timeouts of pos-order's calls to pos-tax's plausibility check and evidence-rules read, and the {@code Retry-After}
 * of 503 {@code TAX_CHECK_UNAVAILABLE} (CAP:550 S32d item 6; ADR-0017 §1).
 *
 * <p>ADR-0017 §1 requires {@code Retry-After} to be at least as long as any wait the service already spent. The wait is
 * at most connect + read, so startup fails, naming {@value #RETRY_AFTER_PROPERTY}, when the retry-after value is below
 * connect + read rounded up to whole seconds.
 *
 * @param connectTimeout     {@code pos.order.tax-check.connect-timeout-ms}, default 1 s
 * @param readTimeout        {@code pos.order.tax-check.read-timeout-ms}, default 3 s
 * @param retryAfterSeconds  {@value #RETRY_AFTER_PROPERTY}, default 5
 */
public record TaxCheckSettings(
        @NonNull Duration connectTimeout, @NonNull Duration readTimeout, long retryAfterSeconds) {

    /** The property that sets {@link #retryAfterSeconds}. */
    public static final String RETRY_AFTER_PROPERTY = "pos.order.tax-check.retry-after-seconds";

    public TaxCheckSettings {
        if (connectTimeout.isNegative()
                || connectTimeout.isZero()
                || readTimeout.isNegative()
                || readTimeout.isZero()) {
            throw new IllegalStateException("pos.order.tax-check.connect-timeout-ms and"
                    + " pos.order.tax-check.read-timeout-ms must be positive");
        }
        long waitMillis = connectTimeout.plus(readTimeout).toMillis();
        long minimumSeconds = (waitMillis + 999) / 1000;
        if (retryAfterSeconds < minimumSeconds) {
            throw new IllegalStateException(RETRY_AFTER_PROPERTY + " is " + retryAfterSeconds
                    + " but must be at least " + minimumSeconds
                    + " (connect + read timeouts, rounded up to whole seconds; ADR-0017 §1)");
        }
    }
}
