package com.positivity.gateway.config;

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
 * currently published by pos-security-service at startup. A mismatch means the
 * gateway will silently reject all JWTs with the newer perm_ver, so the
 * application context is terminated immediately if they diverge.
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
            CatalogVersionDto remote = webClient.get()
                    .uri(CATALOG_VERSION_PATH)
                    .retrieve()
                    .bodyToMono(CatalogVersionDto.class)
                    .block();

            if (remote == null) {
                throw new IllegalStateException(
                        "Permission catalog version check returned null from " + CATALOG_VERSION_PATH);
            }

            if (remote.version() != localVersion) {
                throw new IllegalStateException(String.format(
                        "Permission catalog version mismatch: gateway expects %d but security-service reports %d. "
                                + "Update GatewayPermissionCatalog.CATALOG_VERSION and AUTHORITY_BY_BIT to match.",
                        localVersion, remote.version()));
            }

            LOG.info("Permission catalog version check passed: version={} permissions={}",
                    remote.version(), remote.permissionCount());

        } catch (WebClientResponseException e) {
            LOG.error("Permission catalog version check failed — security-service returned HTTP {}: {}",
                    e.getStatusCode(), e.getMessage());
            throw new IllegalStateException("Cannot verify permission catalog version at startup", e);
        }
    }

    private record CatalogVersionDto(int version, int permissionCount) {}
}
