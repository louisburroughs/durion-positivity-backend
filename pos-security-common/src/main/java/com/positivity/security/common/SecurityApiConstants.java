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

    /**
     * HTTP header carrying the mesh service credential on pos-security-service's {@code /internal/**}
     * surface (CAP:550 S16, #2512): the same shared secret as {@link #PERMISSION_SECRET_PROPERTY},
     * which every service already holds, presented on a header of its own.
     */
    public static final String INTERNAL_SECRET_HEADER = "X-Internal-Api-Secret";

    private SecurityApiConstants() {
        // Utility class
    }

    public static boolean hasSecret(String secret) {
        return secret != null && !secret.isBlank();
    }
}
