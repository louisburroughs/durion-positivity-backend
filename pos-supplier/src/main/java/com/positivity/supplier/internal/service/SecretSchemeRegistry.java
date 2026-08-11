package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Scheme-dispatching front door to the registered {@link SecretReferenceResolver} beans, and
 * the single source of truth for which secret-reference schemes are legal (ADR-0050 §4/§6).
 *
 * <p>Two responsibilities, deliberately in one place so they cannot drift:
 *
 * <ul>
 *   <li><strong>Write-time allowlist</strong> — {@link #supportedSchemes()} feeds
 *       {@link AuthReferenceRules}, so the admin API and the YAML bootstrap reject a reference
 *       whose scheme nothing can resolve <em>before it is persisted</em>. Shape validation alone
 *       is not enough: {@code MYDOMAIN:hunter2} is a well-formed {@code scheme:key} string and a
 *       plaintext credential, and without this check it would persist and fail only on the first
 *       outbound call.
 *   <li><strong>Call-time dispatch</strong> — {@link #resolve(String)} parses the reference once
 *       and hands it to the resolver claiming its scheme.
 * </ul>
 *
 * <p>Deliberately <em>not</em> a {@link SecretReferenceResolver} itself: keeping it a distinct
 * type means adding a second scheme resolver never makes by-type injection ambiguous, and
 * callers that need dispatch state that by depending on this registry.
 *
 * <p>The allowlist is intentionally not configurable per deployment. It is exactly the set of
 * schemes the deployed resolver beans support, so adding a scheme is a bean addition; a property
 * could otherwise widen the allowlist past what is resolvable.
 */
@Component
public class SecretSchemeRegistry {

    private final Map<String, SecretReferenceResolver> resolversByScheme;
    private final Set<String> supportedSchemes;

    /**
     * @param resolvers every {@link SecretReferenceResolver} bean in the context; at least one
     *     is required, and no two may claim the same scheme
     * @throws IllegalStateException when no resolver is registered, a resolver declares a blank
     *     scheme, or two resolvers claim the same scheme — a silently shadowed resolver would
     *     make credential resolution depend on bean ordering (mirrors the duplicate-registration
     *     startup failure of {@code AdapterRegistry})
     */
    public SecretSchemeRegistry(@NonNull List<SecretReferenceResolver> resolvers) {
        Objects.requireNonNull(resolvers, "resolvers must not be null");
        Map<String, SecretReferenceResolver> byScheme = new LinkedHashMap<>();
        Map<String, String> ownerByScheme = new HashMap<>();
        for (SecretReferenceResolver resolver : resolvers) {
            String scheme = resolver.scheme();
            if (scheme == null || scheme.isBlank()) {
                throw new IllegalStateException(
                        "SecretReferenceResolver " + resolver.getClass().getName() + " declares a blank scheme");
            }
            String previousOwner = ownerByScheme.put(scheme, resolver.getClass().getName());
            if (previousOwner != null) {
                throw new IllegalStateException("Duplicate secret reference scheme '" + scheme + "' claimed by "
                        + previousOwner + " and " + resolver.getClass().getName()
                        + "; each scheme must have exactly one resolver");
            }
            byScheme.put(scheme, resolver);
        }
        if (byScheme.isEmpty()) {
            throw new IllegalStateException(
                    "No SecretReferenceResolver beans registered; secret references could never resolve");
        }
        this.resolversByScheme = Map.copyOf(byScheme);
        // TreeSet, not Set.copyOf: Set.copyOf leaves iteration order unspecified, which would make
        // the operator-facing "supported schemes" text vary between runs.
        this.supportedSchemes = Collections.unmodifiableSet(new TreeSet<>(byScheme.keySet()));
    }

    /**
     * The legal secret-reference schemes — exactly those a registered resolver can resolve.
     *
     * @return the supported scheme tokens (e.g. {@code ["env"]}); never empty
     */
    @NonNull
    public Set<String> supportedSchemes() {
        return supportedSchemes;
    }

    /**
     * Whether a reference using this scheme may be persisted.
     *
     * @param scheme the scheme token, without the {@code :} separator
     * @return {@code true} when a registered resolver claims the scheme
     */
    public boolean supports(@NonNull String scheme) {
        return resolversByScheme.containsKey(scheme);
    }

    /**
     * Renders the supported schemes for inclusion in an operator-facing error message.
     *
     * @return e.g. {@code "env:"} or {@code "env:, vault:"}, in stable alphabetical order
     */
    @NonNull
    public String describeSupportedSchemes() {
        List<String> rendered = new ArrayList<>();
        for (String scheme : supportedSchemes) {
            rendered.add(scheme + ":");
        }
        return String.join(", ", rendered);
    }

    /**
     * Resolves a secret reference through the resolver claiming its scheme.
     *
     * @param reference the raw {@code scheme:key} reference (e.g. {@code env:VAR_NAME})
     * @return the resolved secret value; never {@code null}, never logged
     * @throws SupplierConfigurationException {@code SUPPLIER_SECRET_REFERENCE_INVALID} when the
     *     text is not of {@code scheme:key} shape, {@code SUPPLIER_UNKNOWN_SECRET_SCHEME} when
     *     no resolver claims the scheme, or whatever the delegate raises — messages carry the
     *     reference only, never a secret value
     */
    @NonNull
    public String resolve(@NonNull String reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        SecretReference parsed;
        try {
            parsed = SecretReference.parse(reference);
        } catch (IllegalArgumentException ex) {
            throw new SupplierConfigurationException(
                    SupplierConfigurationException.SECRET_REFERENCE_INVALID,
                    "Secret reference '" + reference + "' is malformed: " + ex.getMessage());
        }
        SecretReferenceResolver resolver = resolversByScheme.get(parsed.scheme());
        if (resolver == null) {
            throw new SupplierConfigurationException(
                    SupplierConfigurationException.UNKNOWN_SECRET_SCHEME,
                    "Secret reference scheme '" + parsed.scheme() + "' is not supported; supported schemes: "
                            + describeSupportedSchemes());
        }
        return resolver.resolve(reference);
    }
}
