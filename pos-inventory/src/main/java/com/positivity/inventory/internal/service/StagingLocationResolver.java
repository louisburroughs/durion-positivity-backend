package com.positivity.inventory.internal.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Resolves the staging location goods are received into, shared by every caller that needs to
 * know where staged stock lives: the fallback chain is a configured property, then a per-site
 * default served from the {@code location_ref} replica, then a hardcoded default.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StagingLocationResolver {

    private static final UUID DEFAULT_STAGING_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final SiteDefaultsService siteDefaultsService;

    @Value("${pos.inventory.receiving.site-id:}")
    private String configuredSiteId;

    @Value("${pos.inventory.receiving.staging-location-id:}")
    private String configuredStagingLocationId;

    @NonNull
    public UUID resolveStagingLocationId() {
        UUID fallbackStagingLocationId = resolveLocationId(
                configuredStagingLocationId,
                DEFAULT_STAGING_LOCATION_ID,
                "pos.inventory.receiving.staging-location-id");

        UUID siteId = resolveRequestScopedSiteId().orElseGet(this::resolveConfiguredSiteId);
        if (siteId == null) {
            return fallbackStagingLocationId;
        }

        return siteDefaultsService.getDefaultStagingLocationId(siteId).orElse(fallbackStagingLocationId);
    }

    private Optional<UUID> resolveRequestScopedSiteId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes requestAttributes)) {
            return Optional.empty();
        }

        HttpServletRequest request = requestAttributes.getRequest();
        Optional<UUID> fromHeader = parseSiteId(request.getHeader("X-Site-Id"));
        if (fromHeader.isPresent()) {
            return fromHeader;
        }

        Object uriVars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (uriVars instanceof Map<?, ?> values) {
            Object value = values.get("siteId");
            if (value instanceof String siteIdValue) {
                return parseSiteId(siteIdValue);
            }
        }

        return Optional.empty();
    }

    private UUID resolveConfiguredSiteId() {
        if (configuredSiteId == null || configuredSiteId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(configuredSiteId.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "pos.inventory.receiving.site-id must be a valid UUID: " + configuredSiteId, ex);
        }
    }

    private Optional<UUID> parseSiteId(String rawSiteId) {
        if (rawSiteId == null || rawSiteId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(rawSiteId.trim()));
        } catch (IllegalArgumentException ex) {
            log.debug("Ignoring invalid request-scoped siteId value: {}", rawSiteId);
            return Optional.empty();
        }
    }

    private UUID resolveLocationId(String configuredValue, UUID defaultValue, String propertyName) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return defaultValue;
        }
        try {
            return UUID.fromString(configuredValue.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(propertyName + " must be a valid UUID: " + configuredValue, ex);
        }
    }
}
