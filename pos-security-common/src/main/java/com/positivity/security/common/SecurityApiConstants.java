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
     * which every service already holds, presented on a header of its own. The step-up check is not
     * reachable with it; it takes {@link #STEP_UP_SECRET_HEADER} (#2584).
     */
    public static final String INTERNAL_SECRET_HEADER = "X-Internal-Api-Secret";

    /**
     * HTTP header carrying the step-up credential on pos-security-service's {@code POST
     * /internal/v1/auth/step-up} (#2584): a secret of its own, held only by pos-security-service and its
     * one caller, pos-order, so the mesh credential every registering service holds cannot reach the
     * password check.
     */
    public static final String STEP_UP_SECRET_HEADER = "X-Step-Up-Secret";

    /** Environment variable holding the step-up credential (#2584). */
    public static final String STEP_UP_SECRET_ENV_VAR = "POS_SECURITY_STEP_UP_SECRET";

    /** Spring property holding the step-up credential (#2584), in pos-security-service and pos-order. */
    public static final String STEP_UP_SECRET_PROPERTY = "pos.security.step-up-secret";

    private SecurityApiConstants() {
        // Utility class
    }

    public static boolean hasSecret(String secret) {
        return secret != null && !secret.isBlank();
    }
}
