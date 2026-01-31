package com.positivity.inventory.internal.config;

import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Inventory Permission Initializer
 * 
 * Registers all inventory permissions with the central Security Domain registry
 * at startup.
 * 
 * Permissions are defined per:
 * - Issue #37: Adjustment workflow permissions
 * - Clarification #229: Putaway override permissions
 * 
 * Permissions are registered idempotently; existing permissions are skipped.
 */
@Slf4j
@Configuration
public class InventoryPermissionInitializer {

    @Value("${gateway.url:http://localhost:8080}")
    private String gatewayUrl;

    /**
     * Register inventory permissions with Security Domain on application startup
     */
    @Bean
    public ApplicationRunner registerInventoryPermissions(RestClient restClient) {
        return args -> {
            try {
                log.info("Starting inventory permission registration...");

                var request = InventoryPermissionRegistry.buildInventoryPermissionRegistration();

                // Route through gateway: /security-service/v1/permissions/register
                try {
                    var response = restClient.post()
                            .uri(gatewayUrl + "/security-service/v1/permissions/register")
                            .header("X-API-Version", "1")
                            .body(request)
                            .retrieve()
                            .toEntity(Object.class);

                    if (response.getStatusCode().is2xxSuccessful()) {
                        log.info("✓ Inventory permissions registered successfully with Security Domain");
                        log.debug("Registration response: {}", response.getBody());
                    } else {
                        log.warn("⚠ Unexpected response from Security Domain: {}", response.getStatusCode());
                    }
                } catch (Exception e) {
                    log.warn("⚠ Failed to register inventory permissions with Security Domain (non-blocking): {}",
                            e.getMessage());
                    log.debug("Exception details:", e);
                    // Non-blocking: continue startup even if registration fails
                }

            } catch (Exception e) {
                log.error("✗ Error during inventory permission initialization", e);
                // Continue startup even if there are errors
            }
        };
    }

    /**
     * Provide RestClient bean for inter-service communication
     */
    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }
}
