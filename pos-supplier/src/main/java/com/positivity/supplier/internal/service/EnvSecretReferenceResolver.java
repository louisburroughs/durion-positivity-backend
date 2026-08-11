package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link SecretReferenceResolver} for the {@code env:} scheme (ADR-0050 §4): resolves
 * {@code env:VAR_NAME} against process environment variables at call time. Deliberately
 * log-free — resolved values exist only on the call stack, and every failure message carries
 * the reference, never a value.
 *
 * <p>Currently the only registered resolver, so {@code env} is the whole legal scheme allowlist
 * (see {@link SecretSchemeRegistry}). It keeps its own scheme guard as defence in depth for
 * direct callers, even though {@link SecretSchemeRegistry} never routes a foreign scheme here.
 */
@Component
public class EnvSecretReferenceResolver implements SecretReferenceResolver {

    static final String ENV_SCHEME = "env";

    private final UnaryOperator<String> environmentLookup;

    @Autowired
    public EnvSecretReferenceResolver() {
        this(System::getenv);
    }

    /**
     * Test seam: resolve against an explicit environment lookup instead of
     * {@link System#getenv(String)}.
     *
     * @param environmentLookup variable-name to value lookup; {@code null} result = unset
     */
    EnvSecretReferenceResolver(@NonNull UnaryOperator<String> environmentLookup) {
        this.environmentLookup = Objects.requireNonNull(environmentLookup, "environmentLookup must not be null");
    }

    @Override
    @NonNull
    public String scheme() {
        return ENV_SCHEME;
    }

    @Override
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
        if (!ENV_SCHEME.equals(parsed.scheme())) {
            throw new SupplierConfigurationException(
                    SupplierConfigurationException.UNKNOWN_SECRET_SCHEME,
                    "Secret reference scheme '" + parsed.scheme() + "' is not supported; supported schemes: ["
                            + ENV_SCHEME + "]");
        }
        String value = environmentLookup.apply(parsed.key());
        if (value == null || value.isEmpty()) {
            throw new SupplierConfigurationException(
                    SupplierConfigurationException.SECRET_NOT_FOUND,
                    "Secret reference '" + reference + "' resolves to no value: environment variable is unset");
        }
        return value;
    }
}
