package com.positivity.supplier.service.model;

/**
 * Supported vendor authentication schemes (ADR-0050 §4). Each scheme names which secret
 * <em>reference</em> fields of {@link AuthConfigRequest} it requires; references resolve at
 * call time and plaintext credentials never persist or serialize.
 */
public enum SupplierAuthType {
    /** HTTP Basic credentials plus an API key header ({@code usernameRef}, {@code passwordRef}, {@code apiKeyRef}). */
    BASIC_PLUS_APIKEY,
    /** OAuth2 client-credentials grant ({@code tokenUrlRef}, {@code clientIdRef}, {@code clientSecretRef}). */
    OAUTH2_CLIENT_CREDENTIALS,
    /** Static bearer token ({@code bearerTokenRef}). */
    BEARER
}
