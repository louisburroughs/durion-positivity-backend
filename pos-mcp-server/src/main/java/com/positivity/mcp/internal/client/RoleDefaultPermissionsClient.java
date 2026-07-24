package com.positivity.mcp.internal.client;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for {@code pos-security-service}'s role default-permissions endpoint (#782), used to
 * prebuild per-role agent caches with a role's real permission set instead of an AUTHENTICATED-only
 * warm-up.
 *
 * <p>Startup prebuild has no caller JWT, so — following the platform's internal service-to-service
 * pattern (e.g. {@code TaxServiceClient}, {@code PricingClientImpl}) — the call asserts the required
 * authority via {@code X-User}/{@code X-Authorities} headers, which the target trusts on the internal
 * network. Every failure is swallowed: an empty result leaves prebuild on its AUTHENTICATED-only
 * fallback, so warm-up is never worse than before.
 */
@Component
public class RoleDefaultPermissionsClient {

    private static final Logger log = LoggerFactory.getLogger(RoleDefaultPermissionsClient.class);
    private static final String SERVICE_ACTOR = "pos-mcp-server";
    private static final String REQUIRED_AUTHORITY = "security:role:view";

    private final RestClient restClient;

    public RoleDefaultPermissionsClient(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${mcp.security-service.base-url:http://security-service}") String baseUrl) {
        this.restClient = loadBalancedRestClientBuilder.baseUrl(baseUrl).build();
    }

    /**
     * Returns the authority codes the role expands to (ROLE_* plus domain permission codes), or an
     * empty list on any error. Fail-soft by design — never throws.
     */
    public @NonNull List<String> defaultPermissions(@NonNull String role) {
        try {
            RoleDefaultPermissionsResponse response = restClient
                    .get()
                    .uri("/v1/roles/{role}/default-permissions", role)
                    .header("X-User", SERVICE_ACTOR)
                    .header("X-Authorities", REQUIRED_AUTHORITY)
                    .retrieve()
                    .body(RoleDefaultPermissionsResponse.class);
            if (response == null || response.permissions() == null) {
                return List.of();
            }
            return response.permissions();
        } catch (RuntimeException exception) {
            log.warn("Failed to fetch default permissions for role={}: {}", role, exception.getMessage());
            return List.of();
        }
    }

    /** Mirror of {@code pos-security-service}'s response DTO for deserialization. */
    public record RoleDefaultPermissionsResponse(String role, List<String> permissions) {}
}
