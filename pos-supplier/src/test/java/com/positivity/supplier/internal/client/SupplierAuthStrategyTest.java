package com.positivity.supplier.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.enums.SupplierAuthType;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.service.SecretReferenceResolver;
import com.positivity.supplier.internal.service.SecretSchemeRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * Credential application per {@link SupplierAuthType} (ADR-0050 §4). The load-bearing assertions
 * are not "a header was set" but that references are resolved at call time from the registry and
 * that <strong>no resolved value ever reaches an exception message</strong> — a credential in an
 * error string propagates into logs and API envelopes, which is the leak ADR-0050 §4 forbids.
 */
class SupplierAuthStrategyTest {

    private static final String USER = "michelin-user";
    private static final String PASSWORD = "hunter2-actual-secret";
    private static final String API_KEY = "apikey-actual-secret";
    private static final String BEARER = "bearer-actual-secret";

    private final SecretSchemeRegistry registry = registryOf(Map.of(
            "M_USER", USER,
            "M_PASSWORD", PASSWORD,
            "M_APIKEY", API_KEY,
            "M_BEARER", BEARER));

    @Nested
    class BasicPlusApiKey {

        private final BasicPlusApiKeyAuthStrategy strategy = new BasicPlusApiKeyAuthStrategy(registry);

        @Test
        void declaresItsSingleType() {
            assertThat(strategy.supportedType()).isEqualTo(SupplierAuthType.BASIC_PLUS_APIKEY);
        }

        @Test
        void setsBasicAuthAndApiKeyHeaderFromResolvedReferences() {
            HttpHeaders headers = new HttpHeaders();
            strategy.apply(headers, basicConfig("apikey"));

            String expected = "Basic "
                    + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
            assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(expected);
            assertThat(headers.getFirst("apikey")).isEqualTo(API_KEY);
        }

        @Test
        void usesTheFamilyDefaultHeaderWhenNoneConfigured() {
            HttpHeaders headers = new HttpHeaders();
            strategy.apply(headers, basicConfig(null));

            assertThat(headers.getFirst("apikey")).isEqualTo(API_KEY);
        }

        @Test
        void honoursAConfiguredApiKeyHeaderName() {
            HttpHeaders headers = new HttpHeaders();
            strategy.apply(headers, basicConfig("X-API-Key"));

            assertThat(headers.getFirst("X-API-Key")).isEqualTo(API_KEY);
            assertThat(headers.headerNames()).doesNotContain("apikey");
        }

        @Test
        void basicAuthIsUtf8EncodedNotPlatformDefault() {
            SupplierAuthConfigEntity config = basicConfig("apikey");
            SecretSchemeRegistry unicodeRegistry =
                    registryOf(Map.of("M_USER", "üser", "M_PASSWORD", "pä55", "M_APIKEY", API_KEY));

            HttpHeaders headers = new HttpHeaders();
            new BasicPlusApiKeyAuthStrategy(unicodeRegistry).apply(headers, config);

            String expected =
                    "Basic " + Base64.getEncoder().encodeToString("üser:pä55".getBytes(StandardCharsets.UTF_8));
            assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(expected);
        }

        @Test
        void missingRequiredReferenceFailsTypedBeforeAnyNetworkUse() {
            SupplierAuthConfigEntity config = basicConfig("apikey");
            config.setPasswordRef(null);

            assertThatThrownBy(() -> strategy.apply(new HttpHeaders(), config))
                    .isInstanceOf(SupplierConfigurationException.class)
                    .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.AUTH_CONFIG_MISSING)
                    .hasMessageContaining("passwordRef");
        }

        @Test
        void unresolvableReferenceMessageNeverEchoesACredential() {
            SupplierAuthConfigEntity config = basicConfig("apikey");
            config.setPasswordRef("env:NOT_SET_ANYWHERE");

            assertThatThrownBy(() -> strategy.apply(new HttpHeaders(), config))
                    .isInstanceOf(SupplierConfigurationException.class)
                    .hasMessageNotContaining(PASSWORD)
                    .hasMessageNotContaining(API_KEY);
        }
    }

    @Nested
    class Bearer {

        private final BearerTokenAuthStrategy strategy = new BearerTokenAuthStrategy(registry);

        @Test
        void setsBearerFromResolvedReference() {
            HttpHeaders headers = new HttpHeaders();
            strategy.apply(headers, bearerConfig("env:M_BEARER"));

            assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + BEARER);
        }

        @Test
        void missingReferenceFailsTyped() {
            assertThatThrownBy(() -> strategy.apply(new HttpHeaders(), bearerConfig(null)))
                    .isInstanceOf(SupplierConfigurationException.class)
                    .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.AUTH_CONFIG_MISSING)
                    .hasMessageContaining("bearerTokenRef");
        }
    }

    @Nested
    class Dispatch {

        @Test
        void everyAuthTypeHasExactlyOneStrategy() {
            // The startup guard: a type with no strategy would fail on the first exchange using
            // that scheme, possibly months later and only for one supplier.
            SupplierAuthStrategies strategies = new SupplierAuthStrategies(allStrategies());

            for (SupplierAuthType type : SupplierAuthType.values()) {
                assertThat(strategies.forType(type).supportedType()).isEqualTo(type);
            }
        }

        @Test
        void dispatchesByTheAuthConfigType() {
            SupplierAuthStrategies strategies = new SupplierAuthStrategies(allStrategies());

            HttpHeaders headers = new HttpHeaders();
            strategies.apply(headers, bearerConfig("env:M_BEARER"));

            assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + BEARER);
        }

        @Test
        void aTypeWithoutAStrategyFailsStartup() {
            assertThatThrownBy(() -> new SupplierAuthStrategies(List.of(new BearerTokenAuthStrategy(registry))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No SupplierAuthStrategy registered for auth type");
        }

        @Test
        void duplicateStrategiesForOneTypeFailStartup() {
            // Two strategies for one type would make the credentials sent depend on bean ordering.
            assertThatThrownBy(() -> new SupplierAuthStrategies(
                            List.of(new BearerTokenAuthStrategy(registry), new BearerTokenAuthStrategy(registry))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate SupplierAuthStrategy for type BEARER");
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────

    private List<SupplierAuthStrategy> allStrategies() {
        return List.of(
                new BasicPlusApiKeyAuthStrategy(registry),
                new BearerTokenAuthStrategy(registry),
                new OAuth2ClientCredentialsAuthStrategy(
                        registry,
                        org.springframework.web.client.RestClient.builder(),
                        java.time.Clock.systemUTC(),
                        30));
    }

    private static SupplierAuthConfigEntity basicConfig(String apiKeyHeader) {
        SupplierAuthConfigEntity config = new SupplierAuthConfigEntity();
        config.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a61"));
        config.setName("ediwheel-basic");
        config.setType(SupplierAuthType.BASIC_PLUS_APIKEY);
        config.setUsernameRef("env:M_USER");
        config.setPasswordRef("env:M_PASSWORD");
        config.setApiKeyRef("env:M_APIKEY");
        config.setApiKeyHeader(apiKeyHeader);
        return config;
    }

    /**
     * A registry over a fixed map, claiming the {@code env} scheme. Used instead of the real
     * {@code EnvSecretReferenceResolver} because its test-seam constructor is package-private to
     * {@code internal.service} — widening production visibility for a test would be the wrong trade.
     */
    private static SecretSchemeRegistry registryOf(Map<String, String> values) {
        return new SecretSchemeRegistry(List.of(new MapSecretResolver(values)));
    }

    private record MapSecretResolver(Map<String, String> values) implements SecretReferenceResolver {

        @Override
        @NonNull
        public String scheme() {
            return "env";
        }

        @Override
        @NonNull
        public String resolve(@NonNull String reference) {
            String key = reference.substring(reference.indexOf(':') + 1);
            String value = values.get(key);
            if (value == null) {
                throw new SupplierConfigurationException(
                        SupplierConfigurationException.SECRET_NOT_FOUND,
                        "Secret reference '" + reference + "' resolves to no value");
            }
            return value;
        }
    }

    private static SupplierAuthConfigEntity bearerConfig(String bearerTokenRef) {
        SupplierAuthConfigEntity config = new SupplierAuthConfigEntity();
        config.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a62"));
        config.setName("vendor-bearer");
        config.setType(SupplierAuthType.BEARER);
        config.setBearerTokenRef(bearerTokenRef);
        return config;
    }
}
