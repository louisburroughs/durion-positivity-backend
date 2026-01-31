package com.positivity.events;

/**
 * Constants for the Events API security.
 *
 * <p>
 * The Events API uses a shared secret for authentication instead of JWT tokens.
 * This avoids circular dependencies between services during startup while still
 * providing secure authentication for event registration and emission.
 * </p>
 *
 * <h2>Usage in Event Type Initializers</h2>
 * 
 * <pre>{@code
 * &#64;Component
 * public class MyEventTypeInitializer implements ApplicationRunner {
 *
 *     private final RestClient restClient;
 *     private final String apiSecret;
 *
 *     public MyEventTypeInitializer(
 *             RestClient.Builder builder,
 *             &#64;Value("${pos.events.api-secret:}") String apiSecret) {
 *         this.restClient = builder.baseUrl(...).build();
 *         this.apiSecret = apiSecret;
 *     }
 *
 *     private void registerViaHttp(EventTypeRegistration registration) {
 *         var request = restClient.put()
 *                 .uri("/{typeCode}", registration.getTypeCode())
 *                 .contentType(MediaType.APPLICATION_JSON)
 *                 .body(registration);
 *
 *         EventsApiConstants.addSecretHeader(request, apiSecret);
 *
 *         request.retrieve().toBodilessEntity();
 *     }
 * }
 * }</pre>
 */
public final class EventsApiConstants {

    /**
     * HTTP header name for the shared API secret.
     */
    public static final String SECRET_HEADER = "X-Events-Api-Secret";

    /**
     * Environment variable name for the shared API secret.
     */
    public static final String SECRET_ENV_VAR = "POS_EVENTS_API_SECRET";

    /**
     * Configuration property name for the shared API secret.
     */
    public static final String SECRET_PROPERTY = "pos.events.api-secret";

    private EventsApiConstants() {
        // Utility class
    }

    /**
     * Check if a secret value is present and non-blank.
     *
     * @param secret the secret to check
     * @return true if the secret is present and non-blank
     */
    public static boolean hasSecret(String secret) {
        return secret != null && !secret.isBlank();
    }
}
