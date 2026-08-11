package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.spi.ExchangeOutcome;
import org.jspecify.annotations.NonNull;

/**
 * The credential-acquisition leg failed at <em>transport</em> level — the OAuth2 token endpoint
 * refused the connection, timed out, rate-limited us (429) or returned a 5xx.
 *
 * <p>Deliberately <strong>not</strong> a {@code SupplierConfigurationException}. Both were previously
 * collapsed into {@code SUPPLIER_AUTH_CONFIG_MISSING}, which had three compounding consequences: the
 * failure was reported as a 409 blaming the operator for an environment variable that was present and
 * correct, it was never retried, and it was indistinguishable from a genuinely absent reference.
 *
 * <p>Nothing reached the vendor's <em>business</em> endpoint, so by ADR-0052 §5 this is unambiguously
 * <strong>pre-send and therefore retryable</strong>: the base client classifies it
 * {@link ExchangeOutcome#PRE_SEND_FAILURE} and returns it, so a caller can consult
 * {@code isSafeToRedispatch()}. A vendor's token endpoint 503-ing for twenty seconds inside a nightly
 * order window must cost a retry, not a night's orders.
 *
 * <p>Credentials that are actually <em>wrong</em> (401/403 from the token endpoint) remain a
 * configuration error, because retrying those forever would just hammer the vendor.
 */
public class SupplierAuthTransportException extends RuntimeException {

    private final String authConfigName;

    public SupplierAuthTransportException(@NonNull String authConfigName, @NonNull String message) {
        super(message);
        this.authConfigName = authConfigName;
    }

    public SupplierAuthTransportException(
            @NonNull String authConfigName, @NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
        this.authConfigName = authConfigName;
    }

    /** The auth config whose token leg failed; a configuration name, never a credential. */
    @NonNull
    public String getAuthConfigName() {
        return authConfigName;
    }
}
