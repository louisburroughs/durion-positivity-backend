package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import org.jspecify.annotations.NonNull;

/**
 * Resolves secret references of exactly one scheme ({@code scheme:key}, ADR-0050 §4) to their
 * plaintext values at call time. Implementations must never log or persist resolved values —
 * resolution output exists only on the call stack of the exchange that needs it.
 *
 * <p>Each implementation is a Spring bean declaring the single scheme it handles via
 * {@link #scheme()}. {@link SecretSchemeRegistry} collects them, dispatches by scheme and —
 * critically — <em>is</em> the write-time allowlist: a reference may only be stored if some
 * registered resolver claims its scheme (ADR-0050 §4/§6). Supporting a new scheme is therefore
 * a bean addition and nothing else, never a configuration property: a deployment must not be
 * able to allowlist a scheme that nothing can resolve, because that pushes the failure back to
 * call time — the exact defect the write-time allowlist closes.
 */
public interface SecretReferenceResolver {

    /**
     * The single reference scheme this resolver handles, without the {@code :} separator (e.g.
     * {@code env}).
     *
     * @return the scheme token; never blank, and unique across all registered resolvers —
     *     {@link SecretSchemeRegistry} fails startup on a duplicate claim
     */
    @NonNull
    String scheme();

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
