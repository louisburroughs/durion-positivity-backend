package com.positivity.inventory.internal.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Resolves the staging location goods are received into, shared by every caller that needs to
 * know where staged stock lives.
 *
 * <h2>Which site's defaults apply (#2009)</h2>
 *
 * The staging location is a property of a <em>site</em>, so the resolver first has to decide which
 * site is being acted on. In order:
 *
 * <ol>
 *   <li>the request's own {@code X-Site-Id} header or {@code {siteId}} URI variable,
 *   <li>the configured {@code pos.inventory.receiving.site-id},
 *   <li>the site of the location the caller passes in — the location its stock is actually at.
 * </ol>
 *
 * <p>The first two are explicit operator overrides and stay ahead of the third. The third is what
 * makes the common case work: putaway generation and receive-into-staging are not site-scoped by
 * path, and no client sends the header, so before #2009 every such call fell through to
 * {@link #DEFAULT_STAGING_LOCATION_ID} and refused a receipt booked into the site's own declared
 * staging location. The entity being acted on already names the site, so the caller passes it and
 * the header becomes optional rather than required.
 *
 * <p>Once a site is chosen, its declared default comes from the {@code location_ref} replica. A
 * site that declares no defaults — and a call with no site at all — falls back to the configured
 * {@code pos.inventory.receiving.staging-location-id}, then to the hardcoded default.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StagingLocationResolver {

    private static final UUID DEFAULT_STAGING_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final SiteDefaultsService siteDefaultsService;
    private final ForecastSiteResolver siteResolver;

    @Value("${pos.inventory.receiving.site-id:}")
    private String configuredSiteId;

    @Value("${pos.inventory.receiving.staging-location-id:}")
    private String configuredStagingLocationId;

    /**
     * The staging location for a caller that names no location of its own: request-scoped or
     * configured site only.
     */
    @NonNull
    public UUID resolveStagingLocationId() {
        return resolveStagingLocationIdFor(null);
    }

    /**
     * The staging location of the site that owns {@code locationOrStorageLocationId}, unless an
     * explicit override names a different site.
     *
     * @param locationOrStorageLocationId a site id or a bin within one — a receipt's location, a
     *     purchase order's ship-to — or null when the caller has none
     */
    @NonNull
    public UUID resolveStagingLocationIdFor(@Nullable UUID locationOrStorageLocationId) {
        UUID fallbackStagingLocationId = resolveLocationId(
                configuredStagingLocationId,
                DEFAULT_STAGING_LOCATION_ID,
                "pos.inventory.receiving.staging-location-id");

        UUID siteId = resolveRequestScopedSiteId()
                .or(() -> Optional.ofNullable(resolveConfiguredSiteId()))
                .orElseGet(() -> siteResolver.resolveForecastSite(locationOrStorageLocationId));
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
