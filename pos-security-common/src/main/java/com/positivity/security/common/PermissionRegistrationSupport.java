package com.positivity.security.common;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Support class for registering service permissions with pos-security-service
 * at startup.
 *
 * <h2>Usage</h2>
 * <p>
 * Extend this class in each service to register its permissions:
 * </p>
 * 
 * <pre>
 * {
 *     &#64;code
 *     &#64;Component
 *     public class CatalogPermissionRegistration extends PermissionRegistrationSupport {
 *
 *         public CatalogPermissionRegistration(RestClient.Builder builder,
 *                 &#64;Value("${pos.security.base-url:http://pos-security-service:8086}") String securityServiceUrl,
 *                 &#64;Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
 *             super(builder, securityServiceUrl, enabled, "permissions.yaml");
 *         }
 *     }
 * }
 * </pre>
 *
 * <h2>Permission Naming Convention</h2>
 * <p>
 * Permissions follow the pattern: {@code domain:resource:action}
 * </p>
 * <ul>
 * <li>{@code domain} - Service domain (e.g., catalog, crm, pricing)</li>
 * <li>{@code resource} - Resource type (e.g., product, party, price_book)</li>
 * <li>{@code action} - Action type (e.g., view, create, edit, delete)</li>
 * </ul>
 *
 * @see PermissionDefinition
 */
public abstract class PermissionRegistrationSupport implements ApplicationRunner {

    private static final int MAX_RETRIES = 5;
    private static final long RETRY_DELAY_MS = 2000;
    private static final Logger logger = LoggerFactory.getLogger(PermissionRegistrationSupport.class);

    private final RestClient restClient;
    private final String domain;
    private final String serviceName;
    private final String version;
    private final boolean enabled;
    private final List<PermissionDefinition> manifestPermissions;
    private final String permissionRegistrationSecret;

    /**
     * Create a new permission registration support instance.
     *
     * @param restClientBuilder  RestClient builder for HTTP requests
     * @param securityServiceUrl Base URL of pos-security-service (e.g.,
     *                           http://pos-security-service:8086)
     * @param domain             Domain name for permissions (e.g., "catalog",
     *                           "crm")
     * @param serviceName        Name of the registering service (e.g.,
     *                           "pos-catalog")
     */
    protected PermissionRegistrationSupport(
            RestClient.Builder restClientBuilder,
            @NonNull String securityServiceUrl,
            @NonNull String domain,
            @NonNull String serviceName) {
        this(restClientBuilder, securityServiceUrl, domain, serviceName, true);
    }

    /**
     * Create a new permission registration support instance.
     *
     * @param restClientBuilder  RestClient builder for HTTP requests
     * @param securityServiceUrl Base URL of pos-security-service (e.g.,
     *                           http://pos-security-service:8086)
     * @param domain             Domain name for permissions (e.g., "catalog",
     *                           "crm")
     * @param serviceName        Name of the registering service (e.g.,
     *                           "pos-catalog")
     * @param enabled            Whether permission registration is enabled
     */
    protected PermissionRegistrationSupport(
            RestClient.Builder restClientBuilder,
            @NonNull String securityServiceUrl,
            @NonNull String domain,
            @NonNull String serviceName,
            boolean enabled) {
        this(restClientBuilder, securityServiceUrl, domain, serviceName, "1.0", enabled, null);
    }

    /**
     * Create a permission registration support instance backed by a classpath
     * manifest.
     *
     * @param restClientBuilder        RestClient builder for HTTP requests
     * @param securityServiceUrl       Base URL of pos-security-service
     * @param enabled                  Whether permission registration is enabled
     * @param permissionsManifestPath  Classpath path to permissions manifest (for
     *                                 example: {@code permissions.yaml})
     */
    protected PermissionRegistrationSupport(
            RestClient.Builder restClientBuilder,
            @NonNull String securityServiceUrl,
            boolean enabled,
            @NonNull String permissionsManifestPath) {
        this(restClientBuilder, securityServiceUrl,
                PermissionManifestLoader.loadFromClasspath(permissionsManifestPath), enabled);
    }

    private PermissionRegistrationSupport(
            RestClient.Builder restClientBuilder,
            @NonNull String securityServiceUrl,
            PermissionManifestLoader.PermissionManifest manifest,
            boolean enabled) {
        this(restClientBuilder,
                securityServiceUrl,
                manifest.domain(),
                manifest.serviceName(),
                manifest.version(),
                enabled,
                manifest.permissions());
    }

    private PermissionRegistrationSupport(
            RestClient.Builder restClientBuilder,
            @NonNull String securityServiceUrl,
            @NonNull String domain,
            @NonNull String serviceName,
            @NonNull String version,
            boolean enabled,
            List<PermissionDefinition> manifestPermissions) {
        this.restClient = restClientBuilder
                .baseUrl(securityServiceUrl + "/v1/permissions/register")
                .build();
        this.domain = domain;
        this.serviceName = serviceName;
        this.version = version;
        this.enabled = enabled;
        this.manifestPermissions = manifestPermissions == null ? null : List.copyOf(manifestPermissions);
        this.permissionRegistrationSecret = resolvePermissionRegistrationSecret();
    }

    /**
     * Get the list of permissions to register for this service.
     * Subclasses may override this method for inline registration definitions.
     *
     * @return list of permission definitions
     */
    protected List<PermissionDefinition> getPermissions() {
        if (manifestPermissions != null) {
            return manifestPermissions;
        }
        throw new IllegalStateException(
                "No manifest permissions configured. Override getPermissions() or use manifest constructor.");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            logger.info("[{}] Permission registration is disabled", serviceName);
            return;
        }

        List<PermissionDefinition> permissions = getPermissions();
        if (permissions == null || permissions.isEmpty()) {
            logger.info("[{}] No permissions to register", serviceName);
            return;
        }

        logger.info("[{}] Registering {} permissions with security service...",
                serviceName, permissions.size());

        registerWithRetry(permissions);
    }

    /**
     * Register permissions with retry logic for resilience during startup.
     */
    private void registerWithRetry(List<PermissionDefinition> permissions) {
        AtomicInteger attempt = new AtomicInteger(0);

        while (attempt.incrementAndGet() <= MAX_RETRIES) {
            try {
                // Convert to the format expected by pos-security-service
                List<PermissionDto> permissionDtos = permissions.stream()
                        .map(p -> new PermissionDto(p.name(), p.description()))
                        .toList();

                PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                        domain, serviceName, permissionDtos, version);

                var registrationRequest = restClient.post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request);

                if (SecurityApiConstants.hasSecret(permissionRegistrationSecret)) {
                    registrationRequest.header(
                            SecurityApiConstants.PERMISSION_SECRET_HEADER,
                            permissionRegistrationSecret);
                }

                registrationRequest.retrieve().toBodilessEntity();

                logger.info("[{}] Successfully registered {} permissions",
                        serviceName, permissions.size());
                return;

            } catch (RestClientException e) {
                if (attempt.get() < MAX_RETRIES) {
                    logger.warn("[{}] Permission registration attempt {} failed: {}. Retrying in {}ms...",
                            serviceName, attempt.get(), e.getMessage(), RETRY_DELAY_MS);
                    sleep(RETRY_DELAY_MS);
                } else {
                    logger.error("[{}] Permission registration failed after {} attempts: {}. " +
                            "Service will start but permissions may need manual registration.",
                            serviceName, MAX_RETRIES, e.getMessage());
                }
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String resolvePermissionRegistrationSecret() {
        String envSecret = System.getenv(SecurityApiConstants.PERMISSION_SECRET_ENV_VAR);
        if (SecurityApiConstants.hasSecret(envSecret)) {
            return envSecret;
        }

        String propertySecret = System.getProperty(SecurityApiConstants.PERMISSION_SECRET_PROPERTY);
        if (SecurityApiConstants.hasSecret(propertySecret)) {
            return propertySecret;
        }

        return "";
    }

    /**
     * Request body for permission registration (matches pos-security-service DTO).
     */
    public record PermissionRegistrationRequest(
            String domain,
            String serviceName,
            Collection<PermissionDto> permissions,
            String version) {
    }

    /**
     * Permission definition DTO (matches pos-security-service DTO).
     */
    public record PermissionDto(
            String name,
            String description) {
    }
}
