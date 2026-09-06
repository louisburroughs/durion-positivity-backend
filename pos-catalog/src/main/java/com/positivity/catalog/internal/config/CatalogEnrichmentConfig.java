package com.positivity.catalog.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link CatalogEnrichmentProperties} (#1645).
 *
 * <p>Separate from the properties record itself, mirroring {@code LaborGuideProviderConfig}: the
 * record stays a plain binding target with no Spring lifecycle of its own, which is what lets the
 * matcher's tests construct one directly instead of standing up a context to read two numbers.
 */
@Configuration
@EnableConfigurationProperties(CatalogEnrichmentProperties.class)
public class CatalogEnrichmentConfig {}
