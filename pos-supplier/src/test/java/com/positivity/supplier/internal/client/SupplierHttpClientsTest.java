package com.positivity.supplier.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The shared vendor transport, and the token leg's use of it.
 *
 * <h2>Why the factory type is asserted rather than assumed</h2>
 *
 * The module's retry classification rests on telling a connect timeout (never transmitted, safe to retry) from
 * a read timeout (may have been transmitted, must not be), and that is only type-safe because the factory
 * wraps {@code java.net.http.HttpClient}. A silent revert to {@code SimpleClientHttpRequestFactory} — the
 * fleet default, and what the OAuth2 leg used until this class existed — would collapse both into
 * {@code SocketTimeoutException} and break the distinction without failing any behavioural test, because H2
 * and a local socket fixture never produce the ambiguous case.
 *
 * <h2>And why the timeout values are asserted against the yaml</h2>
 *
 * {@code pos.supplier.oauth2.connect-timeout-millis} and {@code read-timeout-millis} reach the strategy through
 * {@code @Value} keys, which are strings. A typo in either would fall back to the annotation default with the
 * whole suite green and no configuration error anywhere — the same class of silent-default failure as the
 * encryption key that nothing bound.
 */
class SupplierHttpClientsTest {

    @Nested
    @DisplayName("clients are cached per timeout pair and never follow redirects")
    class Caching {

        @Test
        void returnsTheSameInstanceForTheSameTimeoutPair() {
            SupplierHttpClients clients = new SupplierHttpClients();

            assertThat(clients.forTimeouts(5000, 30000))
                    .as("one client per timeout pair: two profiles with identical timeouts share a connection"
                            + " pool, and a per-binding cache would multiply pools for nothing")
                    .isSameAs(clients.forTimeouts(5000, 30000));
        }

        @Test
        void returnsDistinctInstancesForDifferentTimeoutPairs() {
            SupplierHttpClients clients = new SupplierHttpClients();

            assertThat(clients.forTimeouts(5000, 30000)).isNotSameAs(clients.forTimeouts(2000, 30000));
            assertThat(clients.forTimeouts(5000, 30000)).isNotSameAs(clients.forTimeouts(5000, 10000));
        }

        @Test
        void usesTheJdkFactorySoConnectAndReadTimeoutsStayDistinguishable() throws Exception {
            ClientHttpRequestFactory factory = factoryOf(new SupplierHttpClients().forTimeouts(4321, 8765));

            assertThat(factory)
                    .as("SimpleClientHttpRequestFactory reports both timeouts as SocketTimeoutException,"
                            + " separable only by message text; JdkClientHttpRequestFactory raises"
                            + " HttpConnectTimeoutException as a distinct type, which is what the ADR-0052 §5"
                            + " classification depends on")
                    .isInstanceOf(JdkClientHttpRequestFactory.class);
        }

        @Test
        void refusesRedirects() throws Exception {
            HttpClient httpClient = httpClientOf(new SupplierHttpClients().forTimeouts(5000, 30000));

            assertThat(httpClient.followRedirects())
                    .as("a followed redirect would replay a state-creating POST body -- or, on the token leg,"
                            + " client credentials -- to whatever host the Location header named")
                    .isEqualTo(HttpClient.Redirect.NEVER);
        }

        @Test
        void appliesTheConnectTimeoutItWasGiven() throws Exception {
            HttpClient httpClient = httpClientOf(new SupplierHttpClients().forTimeouts(4321, 8765));

            assertThat(httpClient.connectTimeout()).contains(Duration.ofMillis(4321));
        }
    }

    @Nested
    @DisplayName("the token leg's timeout properties actually exist")
    class TokenLegConfiguration {

        private static final Path STRATEGY = Path.of(
                "src/main/java/com/positivity/supplier/internal/client/OAuth2ClientCredentialsAuthStrategy.java");
        private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");

        /**
         * A {@code @Value} key is a string, so a typo silently yields the annotation default and no error. This
         * pins the three token-leg keys to their declarations in {@code application.yml} — the same defect
         * shape as the encryption key, where the property the failure message named was bound by nothing.
         */
        @Test
        void everyTokenLegValueKeyIsDeclaredInApplicationYml() throws Exception {
            String strategy = Files.readString(STRATEGY);
            String yaml = Files.readString(APPLICATION_YML);

            for (String key : new String[] {"expiry-skew-seconds", "connect-timeout-millis", "read-timeout-millis"}) {
                assertThat(strategy)
                        .as("the strategy must read pos.supplier.oauth2.%s", key)
                        .contains("${pos.supplier.oauth2." + key + ":");
                assertThat(yaml)
                        .as(
                                "pos.supplier.oauth2.%s must be declared in application.yml, or a typo in the"
                                        + " @Value key falls back to the default with the suite green",
                                key)
                        .contains(key + ": ${");
            }
        }

        /**
         * Asserted on the constructor's parameter TYPES, not on source text. The first version of this grepped
         * for "RestClient.Builder" and failed on the comment that explains why the builder is no longer used --
         * a test that breaks when you document the fix is testing the prose, not the code.
         */
        @Test
        void theTokenLegTakesTheVendorTransportNotThePlatformBuilder() {
            Class<?>[] parameters =
                    OAuth2ClientCredentialsAuthStrategy.class.getDeclaredConstructors()[0].getParameterTypes();

            assertThat(parameters)
                    .as("the token leg must be built from the shared vendor transport")
                    .contains(SupplierHttpClients.class);
            assertThat(parameters)
                    .as("the platform builder carries 2s/5s in-cluster budgets and"
                            + " SimpleClientHttpRequestFactory. The token leg took it until SupplierHttpClients"
                            + " existed, which meant the request that FETCHES a vendor credential ran on a"
                            + " different transport from the one that uses it")
                    .doesNotContain(RestClient.Builder.class);
        }
    }

    // ── Reflection helpers ──────────────────────────────────────────────────────────

    /** {@code RestClient} exposes neither its factory nor its timeouts, and both are worth pinning. */
    private static ClientHttpRequestFactory factoryOf(RestClient client) throws Exception {
        Field field = findField(client.getClass(), ClientHttpRequestFactory.class);
        field.setAccessible(true);
        return (ClientHttpRequestFactory) field.get(client);
    }

    private static HttpClient httpClientOf(RestClient client) throws Exception {
        ClientHttpRequestFactory factory = factoryOf(client);
        Field field = findField(factory.getClass(), HttpClient.class);
        field.setAccessible(true);
        return (HttpClient) field.get(factory);
    }

    private static Field findField(Class<?> type, Class<?> fieldType) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (fieldType.isAssignableFrom(field.getType())) {
                    return field;
                }
            }
        }
        throw new AssertionError("no " + fieldType.getSimpleName() + " field on " + type
                + " -- Spring's internals changed shape; re-point this helper rather than deleting the"
                + " assertions, which cover a distinction no behavioural test can reach");
    }
}
