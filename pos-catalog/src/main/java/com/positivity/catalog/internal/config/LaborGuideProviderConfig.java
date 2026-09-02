package com.positivity.catalog.internal.config;

import com.positivity.catalog.internal.adapter.mockguide.MockGuideLaborTimeAdapter;
import com.positivity.catalog.internal.spi.LaborTimeProviderPort;
import com.positivity.catalog.internal.spi.model.LaborTimeProviderDescriptor;
import com.positivity.catalog.internal.spi.model.LaborTimeProviderDescriptor.LicenseMode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the configured labor-time provider ports (#1569 Phase 1, sourcing plan §3.3).
 *
 * <p>Providers are external vendors reached by configured base URL — never Eureka, never the
 * gateway — so this deliberately uses a plain {@link RestClient} builder, not the load-balanced
 * one (the pos-vehicle-fitment inline-vendor-call-on-the-read-path mistake is what §5.2 forbids;
 * these ports are only ever called from the import runner and the bounded QUERY_ONLY path).
 *
 * <p>A misconfigured provider entry fails startup loudly: a roster that silently drops a source
 * would let a deployment believe it is importing a guide it is not.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(LaborGuideProviderProperties.class)
public class LaborGuideProviderConfig {

    private static final int DEFAULT_PRECEDENCE = 100;
    private static final long DEFAULT_CACHE_TTL_SECONDS = 300;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 2_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 10_000;

    /** Ports keyed by source code, in configuration order. Empty when no providers configured. */
    @Bean
    public Map<String, LaborTimeProviderPort> laborTimeProviders(LaborGuideProviderProperties properties) {
        Map<String, LaborTimeProviderPort> ports = new LinkedHashMap<>();
        if (Boolean.FALSE.equals(properties.enabled())) {
            log.info("Labor-guide providers disabled by pos.catalog.labor-guide.enabled=false");
            return ports;
        }
        List<LaborGuideProviderProperties.ProviderSpec> specs =
                properties.providers() == null ? List.of() : properties.providers();
        for (LaborGuideProviderProperties.ProviderSpec spec : specs) {
            if (Boolean.FALSE.equals(spec.enabled())) {
                continue;
            }
            if (spec.sourceCode() == null || spec.sourceCode().isBlank()) {
                throw new IllegalStateException("labor-guide provider entry is missing source-code");
            }
            String sourceCode = spec.sourceCode().trim().toUpperCase(Locale.ROOT);
            if (spec.baseUrl() == null || spec.baseUrl().isBlank()) {
                throw new IllegalStateException("labor-guide provider " + sourceCode + " is missing base-url");
            }
            if (ports.containsKey(sourceCode)) {
                throw new IllegalStateException("labor-guide provider " + sourceCode + " is configured twice");
            }
            LaborTimeProviderDescriptor descriptor = new LaborTimeProviderDescriptor(
                    sourceCode,
                    sourceCode + " labor guide",
                    parseLicenseMode(sourceCode, spec.licenseMode()),
                    spec.defaultPrecedence() == null ? DEFAULT_PRECEDENCE : spec.defaultPrecedence());
            ports.put(sourceCode, buildAdapter(spec, descriptor));
            log.info(
                    "Labor-guide provider {} registered: adapter={}, mode={}, baseUrl={}",
                    sourceCode,
                    spec.adapter(),
                    descriptor.licenseMode(),
                    spec.baseUrl());
        }
        return ports;
    }

    /** QUERY_ONLY cache TTLs by source code; license terms drive these (sourcing plan §5.4). */
    @Bean
    public Map<String, Duration> laborTimeProviderCacheTtls(LaborGuideProviderProperties properties) {
        Map<String, Duration> ttls = new LinkedHashMap<>();
        List<LaborGuideProviderProperties.ProviderSpec> specs =
                properties.providers() == null ? List.of() : properties.providers();
        for (LaborGuideProviderProperties.ProviderSpec spec : specs) {
            if (spec.sourceCode() != null) {
                ttls.put(
                        spec.sourceCode().trim().toUpperCase(Locale.ROOT),
                        Duration.ofSeconds(
                                spec.cacheTtlSeconds() == null ? DEFAULT_CACHE_TTL_SECONDS : spec.cacheTtlSeconds()));
            }
        }
        return ttls;
    }

    private static LicenseMode parseLicenseMode(String sourceCode, String raw) {
        if (raw == null || raw.isBlank()) {
            return LicenseMode.STORE;
        }
        try {
            return LicenseMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "labor-guide provider " + sourceCode + " has unknown license-mode: " + raw, e);
        }
    }

    private static LaborTimeProviderPort buildAdapter(
            LaborGuideProviderProperties.ProviderSpec spec, LaborTimeProviderDescriptor descriptor) {
        String adapter = spec.adapter() == null ? "" : spec.adapter().trim().toLowerCase(Locale.ROOT);
        if (MockGuideLaborTimeAdapter.ADAPTER_KEY.equals(adapter)) {
            RestClient restClient = RestClient.builder()
                    .baseUrl(spec.baseUrl().trim())
                    .requestFactory(timeoutFactory(spec))
                    .build();
            return new MockGuideLaborTimeAdapter(descriptor, restClient);
        }
        throw new IllegalStateException("labor-guide provider " + descriptor.sourceCode() + " names unknown adapter '"
                + spec.adapter() + "'; known: " + MockGuideLaborTimeAdapter.ADAPTER_KEY);
    }

    private static SimpleClientHttpRequestFactory timeoutFactory(LaborGuideProviderProperties.ProviderSpec spec) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(
                spec.connectTimeoutMs() == null ? DEFAULT_CONNECT_TIMEOUT_MS : spec.connectTimeoutMs()));
        factory.setReadTimeout(
                Duration.ofMillis(spec.readTimeoutMs() == null ? DEFAULT_READ_TIMEOUT_MS : spec.readTimeoutMs()));
        return factory;
    }
}
