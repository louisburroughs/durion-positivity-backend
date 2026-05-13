package com.positivity.gateway.config;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Verifies that GatewayPermissionCatalog.CATALOG_VERSION matches the version
 * currently published by pos-security-service at startup.
 *
 * A version MISMATCH is fatal — the gateway will silently reject all JWTs with the
 * newer perm_ver so the context is terminated immediately.
 *
 * Connectivity failures (503, connection refused) are non-fatal: they indicate the
 * security service hasn't registered with Eureka yet or is still starting. The
 * warning is logged and the check is skipped so the gateway can proceed.
 */
@Component
public class PermissionVersionStartupCheck {

    private static final Logger LOG = LoggerFactory.getLogger(PermissionVersionStartupCheck.class);
    private static final String CATALOG_VERSION_PATH = "/v1/permissions/catalog-version";

    private final WebClient webClient;

    public PermissionVersionStartupCheck(
            WebClient.Builder webClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8080}") String securityBaseUrl) {
        this.webClient = webClientBuilder.baseUrl(securityBaseUrl).build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkPermissionVersion() {
        int localVersion = GatewayPermissionCatalog.CATALOG_VERSION;
        try {
            CatalogVersionDto remote = webClient
                    .get()
                    .uri(CATALOG_VERSION_PATH)
                    .retrieve()
                    .bodyToMono(CatalogVersionDto.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();

            if (remote == null) {
                LOG.warn("Permission catalog version check returned null — skipping");
                return;
            }

            if (remote.version() != localVersion) {
                throw new IllegalStateException(String.format(
                        "Permission catalog version mismatch: gateway expects %d but security-service reports %d. "
                                + "Update GatewayPermissionCatalog.CATALOG_VERSION and AUTHORITY_BY_BIT to match.",
                        localVersion, remote.version()));
            }

            LOG.info(
                    "Permission catalog version check passed: version={} permissions={}",
                    remote.version(),
                    remote.permissionCount());

        } catch (WebClientResponseException e) {
            // 4xx means the endpoint exists but something is wrong with the request —
            // treat as fatal since it hints at a real incompatibility.
            if (e.getStatusCode().is4xxClientError()) {
                throw new IllegalStateException(
                        "Permission catalog version check failed with client error: " + e.getStatusCode(), e);
            }
            // 5xx / 503 means the security service is temporarily unavailable.
            LOG.warn(
                    "Permission catalog version check skipped — security-service returned HTTP {} (service may still be starting): {}",
                    e.getStatusCode(),
                    e.getMessage());
        } catch (Exception e) {
            // Connection refused, timeout, etc. — service hasn't started yet.
            LOG.warn(
                    "Permission catalog version check skipped — security-service unreachable at startup: {}",
                    e.getMessage());
        }
    }

    private record CatalogVersionDto(int version, int permissionCount) {}
}
