package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import org.jspecify.annotations.NonNull;

/**
 * Resolves scheme-prefixed secret references ({@code scheme:key}, ADR-0050 §4) to their
 * plaintext values at call time. Implementations must never log or persist resolved values —
 * resolution output exists only on the call stack of the exchange that needs it.
 */
public interface SecretReferenceResolver {

    /**
     * Resolves a secret reference to its plaintext value.
     *
     * @param reference the raw {@code scheme:key} reference (e.g. {@code env:VAR_NAME})
     * @return the resolved secret value; never {@code null}, never logged
     * @throws SupplierConfigurationException when the reference is malformed
     *     ({@code SUPPLIER_SECRET_REFERENCE_INVALID}), names an unsupported scheme
     *     ({@code SUPPLIER_UNKNOWN_SECRET_SCHEME}), or resolves to no value
     *     ({@code SUPPLIER_SECRET_NOT_FOUND}) — messages carry the reference only, never a
     *     secret value
     */
    @NonNull
    String resolve(@NonNull String reference);
}
