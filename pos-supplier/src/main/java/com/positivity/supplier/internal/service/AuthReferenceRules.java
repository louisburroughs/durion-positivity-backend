package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.service.model.AuthConfigRequest;
import com.positivity.supplier.internal.service.model.SupplierAuthType;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Per-{@link SupplierAuthType} secret-reference rules (ADR-0050 §4), shared by the admin service
 * and the YAML bootstrap reconciler. Required references must be present, of {@code scheme:key}
 * shape ({@link SecretReference}) <em>and</em> use a scheme some registered resolver can actually
 * resolve; references the type does not use must be absent — silently storing them would
 * misdescribe the credential set.
 *
 * <p>The scheme allowlist is the load-bearing half. Shape validation alone lets
 * {@code MYDOMAIN:hunter2} through: it is a syntactically valid {@code scheme:key} string and
 * also a plaintext credential, so it would persist and fail only on the first outbound call —
 * exactly the deferred credential leak ADR-0050 §4/§6 forbids. Rejecting at write time keeps
 * unresolvable references out of the database entirely.
 */
public final class AuthReferenceRules {
    private static final String USERNAME_REF = "usernameRef";

    private static final String TOKEN_URL_REF = "tokenUrlRef";

    private static final String PASSWORD_REF = "passwordRef";

    private static final String CLIENT_SECRET_REF = "clientSecretRef";

    private static final String CLIENT_ID_REF = "clientIdRef";

    private static final String BEARER_TOKEN_REF = "bearerTokenRef";

    private static final String API_KEY_REF = "apiKeyRef";

    private AuthReferenceRules() {
        // static rules
    }

    /**
     * Validates the request's secret references against its {@link SupplierAuthType} and the
     * legal scheme allowlist.
     *
     * @param request the auth config payload
     * @param supportedSchemes the schemes a registered resolver can resolve — pass
     *     {@link SecretSchemeRegistry#supportedSchemes()}; never widen this by configuration
     * @throws SupplierValidationException {@code SUPPLIER_AUTH_REFS_INCOMPLETE} for a
     *     missing/extra reference, {@code SUPPLIER_SECRET_REF_MALFORMED} for a value that is not
     *     of {@code scheme:key} shape or whose scheme is outside {@code supportedSchemes}
     */
    public static void validate(@NonNull AuthConfigRequest request, @NonNull Set<String> supportedSchemes) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(supportedSchemes, "supportedSchemes must not be null");
        switch (request.type()) {
            case BASIC_PLUS_APIKEY -> {
                requireRef(request.type(), USERNAME_REF, request.usernameRef(), supportedSchemes);
                requireRef(request.type(), PASSWORD_REF, request.passwordRef(), supportedSchemes);
                requireRef(request.type(), API_KEY_REF, request.apiKeyRef(), supportedSchemes);
                forbidRef(request.type(), TOKEN_URL_REF, request.tokenUrlRef());
                forbidRef(request.type(), CLIENT_ID_REF, request.clientIdRef());
                forbidRef(request.type(), CLIENT_SECRET_REF, request.clientSecretRef());
                forbidRef(request.type(), BEARER_TOKEN_REF, request.bearerTokenRef());
            }
            case OAUTH2_CLIENT_CREDENTIALS -> {
                requireRef(request.type(), TOKEN_URL_REF, request.tokenUrlRef(), supportedSchemes);
                requireRef(request.type(), CLIENT_ID_REF, request.clientIdRef(), supportedSchemes);
                requireRef(request.type(), CLIENT_SECRET_REF, request.clientSecretRef(), supportedSchemes);
                forbidRef(request.type(), USERNAME_REF, request.usernameRef());
                forbidRef(request.type(), PASSWORD_REF, request.passwordRef());
                forbidRef(request.type(), API_KEY_REF, request.apiKeyRef());
                forbidRef(request.type(), BEARER_TOKEN_REF, request.bearerTokenRef());
            }
            case BEARER -> {
                requireRef(request.type(), BEARER_TOKEN_REF, request.bearerTokenRef(), supportedSchemes);
                forbidRef(request.type(), USERNAME_REF, request.usernameRef());
                forbidRef(request.type(), PASSWORD_REF, request.passwordRef());
                forbidRef(request.type(), API_KEY_REF, request.apiKeyRef());
                forbidRef(request.type(), TOKEN_URL_REF, request.tokenUrlRef());
                forbidRef(request.type(), CLIENT_ID_REF, request.clientIdRef());
                forbidRef(request.type(), CLIENT_SECRET_REF, request.clientSecretRef());
            }
        }
    }

    private static void requireRef(
            @NonNull SupplierAuthType type,
            @NonNull String field,
            @Nullable String value,
            @NonNull Set<String> supportedSchemes) {
        if (value == null || value.isBlank()) {
            throw new SupplierValidationException(
                    SupplierValidationException.AUTH_REFS_INCOMPLETE,
                    "Auth type " + type + " requires " + field + " (ADR-0050 §4)");
        }
        SecretReference parsed;
        try {
            parsed = SecretReference.parse(value);
        } catch (IllegalArgumentException ex) {
            throw new SupplierValidationException(
                    SupplierValidationException.SECRET_REF_MALFORMED,
                    field + " must be a secret reference of 'scheme:key' shape (e.g. env:VAR_NAME),"
                            + " never a plaintext credential (ADR-0050 §4)");
        }
        if (!supportedSchemes.contains(parsed.scheme())) {
            // Deliberately does NOT name the offending scheme. A rejected value is very often a
            // plaintext credential that merely contains a colon, and the "scheme" is then its
            // leading fragment: "Passw0rd:2026" would put "Passw0rd" into the startup log and the
            // API error envelope. The field name plus the supported list is enough for an operator
            // to correct their own configuration, and it cannot leak (ADR-0050 §4/§6).
            throw new SupplierValidationException(
                    SupplierValidationException.SECRET_REF_MALFORMED,
                    field + " uses a secret reference scheme that no configured resolver supports;"
                            + " supported schemes: " + describe(supportedSchemes)
                            + ". A value in an unsupported scheme is treated as a plaintext credential and"
                            + " rejected (ADR-0050 §4)");
        }
    }

    @NonNull
    private static String describe(@NonNull Set<String> supportedSchemes) {
        return supportedSchemes.stream().sorted().map(scheme -> scheme + ":").collect(Collectors.joining(", "));
    }

    private static void forbidRef(@NonNull SupplierAuthType type, @NonNull String field, @Nullable String value) {
        if (value != null) {
            throw new SupplierValidationException(
                    SupplierValidationException.AUTH_REFS_INCOMPLETE,
                    "Auth type " + type + " does not use " + field + "; it must be absent (ADR-0050 §4)");
        }
    }
}
