package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.service.model.AuthConfigRequest;
import com.positivity.supplier.service.model.SupplierAuthType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Per-{@link SupplierAuthType} secret-reference completeness rules (ADR-0050 §4), shared by
 * the admin service and the YAML bootstrap reconciler: required references must be present
 * and of {@code scheme:key} shape ({@link SecretReference}); references the type does not use
 * must be absent — silently storing them would misdescribe the credential set. Plaintext
 * credentials never pass (a plaintext-looking value has no scheme prefix).
 */
public final class AuthReferenceRules {

    private AuthReferenceRules() {
        // static rules
    }

    /**
     * Validates the request's secret references against its {@link SupplierAuthType}.
     *
     * @param request the auth config payload
     * @throws SupplierValidationException {@code SUPPLIER_AUTH_REFS_INCOMPLETE} for a
     *     missing/extra reference, {@code SUPPLIER_SECRET_REF_MALFORMED} for a value that is
     *     not of {@code scheme:key} shape
     */
    public static void validate(@NonNull AuthConfigRequest request) {
        switch (request.type()) {
            case BASIC_PLUS_APIKEY -> {
                requireRef(request.type(), "usernameRef", request.usernameRef());
                requireRef(request.type(), "passwordRef", request.passwordRef());
                requireRef(request.type(), "apiKeyRef", request.apiKeyRef());
                forbidRef(request.type(), "tokenUrlRef", request.tokenUrlRef());
                forbidRef(request.type(), "clientIdRef", request.clientIdRef());
                forbidRef(request.type(), "clientSecretRef", request.clientSecretRef());
                forbidRef(request.type(), "bearerTokenRef", request.bearerTokenRef());
            }
            case OAUTH2_CLIENT_CREDENTIALS -> {
                requireRef(request.type(), "tokenUrlRef", request.tokenUrlRef());
                requireRef(request.type(), "clientIdRef", request.clientIdRef());
                requireRef(request.type(), "clientSecretRef", request.clientSecretRef());
                forbidRef(request.type(), "usernameRef", request.usernameRef());
                forbidRef(request.type(), "passwordRef", request.passwordRef());
                forbidRef(request.type(), "apiKeyRef", request.apiKeyRef());
                forbidRef(request.type(), "bearerTokenRef", request.bearerTokenRef());
            }
            case BEARER -> {
                requireRef(request.type(), "bearerTokenRef", request.bearerTokenRef());
                forbidRef(request.type(), "usernameRef", request.usernameRef());
                forbidRef(request.type(), "passwordRef", request.passwordRef());
                forbidRef(request.type(), "apiKeyRef", request.apiKeyRef());
                forbidRef(request.type(), "tokenUrlRef", request.tokenUrlRef());
                forbidRef(request.type(), "clientIdRef", request.clientIdRef());
                forbidRef(request.type(), "clientSecretRef", request.clientSecretRef());
            }
        }
    }

    private static void requireRef(@NonNull SupplierAuthType type, @NonNull String field, @Nullable String value) {
        if (value == null || value.isBlank()) {
            throw new SupplierValidationException(
                    SupplierValidationException.AUTH_REFS_INCOMPLETE,
                    "Auth type " + type + " requires " + field + " (ADR-0050 §4)");
        }
        if (!SecretReference.isWellFormed(value)) {
            throw new SupplierValidationException(
                    SupplierValidationException.SECRET_REF_MALFORMED,
                    field + " must be a secret reference of 'scheme:key' shape (e.g. env:VAR_NAME),"
                            + " never a plaintext credential (ADR-0050 §4)");
        }
    }

    private static void forbidRef(@NonNull SupplierAuthType type, @NonNull String field, @Nullable String value) {
        if (value != null) {
            throw new SupplierValidationException(
                    SupplierValidationException.AUTH_REFS_INCOMPLETE,
                    "Auth type " + type + " does not use " + field + "; it must be absent (ADR-0050 §4)");
        }
    }
}
