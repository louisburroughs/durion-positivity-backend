package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.enums.SupplierAuthType;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.spi.ExchangeContext;
import com.positivity.supplier.internal.spi.ExchangeOutcome;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;

/**
 * Applies one {@link SupplierAuthType}'s credentials to an outbound request (ADR-0050 §4).
 *
 * <p>Two invariants every implementation owes:
 *
 * <ul>
 *   <li><strong>Resolve at call time.</strong> Only secret <em>references</em> are persisted; the
 *       plaintext is resolved per exchange and exists on the call stack only. Nothing may cache a
 *       resolved credential beyond the scope of the call that needs it — the sole exception is an
 *       OAuth2 access token, which is a vendor-issued, short-lived artifact rather than a stored
 *       credential.
 *   <li><strong>Never log a resolved value.</strong> No implementation may log, wrap in an
 *       exception message, or place into {@link ExchangeContext} any resolved secret. Failure
 *       messages name the reference, never its value.
 * </ul>
 *
 * <p>One strategy bean per auth type; {@code SupplierAuthStrategies} dispatches by
 * {@link #supportedType()} and fails startup if a type has no strategy or two claim the same one.
 */
public interface SupplierAuthStrategy {

    /**
     * The single auth type this strategy handles.
     *
     * @return the supported type; unique across all registered strategies
     */
    @NonNull
    SupplierAuthType supportedType();

    /**
     * Adds this auth type's credential headers to {@code headers}.
     *
     * @param headers the outbound headers to mutate
     * @param authConfig the binding's auth config, carrying secret references only
     * @throws SupplierConfigurationException when a reference cannot be resolved — raised
     *     <em>before</em> any network I/O so the failure classifies as
     *     {@link ExchangeOutcome#CONFIGURATION_ERROR}, never as a transport error
     */
    void apply(@NonNull HttpHeaders headers, @NonNull SupplierAuthConfigEntity authConfig);
}
