package com.positivity.security.common;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
 *                 &#64;Value("${pos.security.base-url}") String securityServiceUrl) {
 *             super(builder, securityServiceUrl, "catalog", "pos-catalog");
 *         }
 *
 *         @Override
 *         protected List<PermissionDefinition> getPermissions() {
 *             return List.of(
 *                     PermissionDefinition.of("catalog:product:view", "View products"),
 *                     PermissionDefinition.of("catalog:product:create", "Create products"),
 *                     PermissionDefinition.of("catalog:product:edit", "Edit products"),
 *                     PermissionDefinition.of("catalog:product:delete", "Delete products"));
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
@Slf4j
public abstract class PermissionRegistrationSupport implements ApplicationRunner {

    private static final int MAX_RETRIES = 5;
    private static final long RETRY_DELAY_MS = 2000;

    private final RestClient restClient;
    private final String domain;
    private final String serviceName;
    private final boolean enabled;

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
        this.restClient = restClientBuilder
                .baseUrl(securityServiceUrl + "/v1/permissions/register")
                .build();
        this.domain = domain;
        this.serviceName = serviceName;
        this.enabled = enabled;
    }

    /**
     * Get the list of permissions to register for this service.
     * Subclasses must implement this method to define their permissions.
     *
     * @return list of permission definitions
     */
    protected abstract List<PermissionDefinition> getPermissions();

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[{}] Permission registration is disabled", serviceName);
            return;
        }

        List<PermissionDefinition> permissions = getPermissions();
        if (permissions == null || permissions.isEmpty()) {
            log.info("[{}] No permissions to register", serviceName);
            return;
        }

        log.info("[{}] Registering {} permissions with security service...",
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
                        domain, serviceName, permissionDtos, "1.0");

                restClient.post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity();

                log.info("[{}] Successfully registered {} permissions",
                        serviceName, permissions.size());
                return;

            } catch (RestClientException e) {
                if (attempt.get() < MAX_RETRIES) {
                    log.warn("[{}] Permission registration attempt {} failed: {}. Retrying in {}ms...",
                            serviceName, attempt.get(), e.getMessage(), RETRY_DELAY_MS);
                    sleep(RETRY_DELAY_MS);
                } else {
                    log.error("[{}] Permission registration failed after {} attempts: {}. " +
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
