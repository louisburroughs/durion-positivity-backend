package com.positivity.supplier.internal.enums;

/**
 * Persistence mirror of {@code service.model.SupplierAuthType} (ADR-0050 §4): the vendor
 * authentication scheme of an auth config.
 */
public enum SupplierAuthType {
    /** HTTP Basic credentials plus an API key header. */
    BASIC_PLUS_APIKEY,
    /** OAuth2 client-credentials grant. */
    OAUTH2_CLIENT_CREDENTIALS,
    /** Static bearer token. */
    BEARER
}
