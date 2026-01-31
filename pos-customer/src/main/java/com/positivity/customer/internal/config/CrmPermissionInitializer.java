package com.positivity.customer.internal.config;

import com.positivity.customer.internal.security.CrmPermissionRegistry;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * CRM Permission Initializer
 * 
 * Registers all CRM permissions with the central Security Domain registry at
 * startup.
 * Permissions are defined per ADR 0002 (CRM Domain Permission Taxonomy).
 * 
 * Permissions are registered idempotently; existing permissions are skipped.
 */
@Slf4j
@Configuration
public class CrmPermissionInitializer {

    @Value("${gateway.url:http://localhost:8080}")
    private String gatewayUrl;

    /**
     * Register CRM permissions with Security Domain on application startup
     */
    @Bean
    public ApplicationRunner registerCrmPermissions(RestClient restClient) {
        return args -> {
            try {
                log.info("Starting CRM permission registration...");

                var request = CrmPermissionRegistry.buildCrmPermissionRegistration();

                // Call security service to register permissions

                // Route through gateway: /security-service/v1/permissions/register

                try {
                    var response = restClient.post()
                            .uri(gatewayUrl + "/security-service/v1/permissions/register")
                            .header("X-API-Version", "1") // Required by gateway
                            .body(request)
                            .retrieve().toEntity(Object.class);

                    if (response.getStatusCode().is2xxSuccessful()) {
                        log.info("✓ CRM permissions registered successfully with Security Domain");
                        log.debug("Registration response: {}", response.getBody());
                    } else {
                        log.warn("⚠ Unexpected response from Security Domain: {}", response.getStatusCode());
                    }
                } catch (Exception e) {
                    log.warn("⚠ Failed to register CRM permissions with Security Domain (non-blocking): {}",
                            e.getMessage());
                    log.debug("Exception details:", e);
                    // Non-blocking: continue startup even if registration fails
                }

            } catch (Exception e) {
                log.error("✗ Error during CRM permission initialization", e);
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
