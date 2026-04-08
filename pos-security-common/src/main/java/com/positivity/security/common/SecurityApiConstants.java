package com.positivity.security.common;

/**
 * Constants for shared security API integration across services.
 */
public final class SecurityApiConstants {

    /**
     * HTTP header name used for shared-secret permission registration.
     */
    public static final String PERMISSION_SECRET_HEADER = "X-Permissions-Api-Secret";

    /**
     * Environment variable for shared-secret permission registration.
     */
    public static final String PERMISSION_SECRET_ENV_VAR = "POS_SECURITY_API_SECRET";

    /**
     * Spring property for shared-secret permission registration.
     */
    public static final String PERMISSION_SECRET_PROPERTY = "pos.security.api-secret";

    private SecurityApiConstants() {
        // Utility class
    }

    public static boolean hasSecret(String secret) {
        return secret != null && !secret.isBlank();
    }
}
