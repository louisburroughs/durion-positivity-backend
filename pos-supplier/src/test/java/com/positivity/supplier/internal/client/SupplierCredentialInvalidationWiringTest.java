package com.positivity.supplier.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.internal.service.SecretSchemeRegistry;
import com.positivity.supplier.internal.spi.SupplierAuthConfigChanged;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Proves the credential-invalidation seam is actually connected.
 *
 * <h2>Why this test exists</h2>
 *
 * The two halves were each covered and the join between them was not. {@code SupplierProfileAdminServiceImpl}
 * was proven to publish {@link SupplierAuthConfigChanged} via {@code @RecordApplicationEvents}, and
 * {@code SupplierAuthStrategies} was proven to fan an invalidation out to every strategy by calling the
 * dispatcher directly — but <strong>nothing asserted that publishing the event reaches the listener</strong>.
 * Deleting the {@code @EventListener} annotation left all 519 tests green while production silently returned
 * to the hour-long stale-token window the seam exists to close.
 *
 * <p>So this drives the only path production uses: publish through an {@link ApplicationEventPublisher} and
 * assert a strategy was asked to evict. A Spring context is required — the annotation does nothing without
 * one, which is precisely the point.
 *
 * <p>Deliberately the narrowest context that can hold the wiring: the dispatcher, one recording strategy, and
 * nothing else. A {@code @SpringBootTest} would prove the same thing while also booting a datasource, Flyway,
 * Eureka and the scheduler, so a failure anywhere in the module would masquerade as a broken seam.
 */
@SpringJUnitConfig
@Import(SupplierCredentialInvalidationWiringTest.WiringConfig.class)
class SupplierCredentialInvalidationWiringTest {

    /** Records what it was asked to invalidate, and nothing else. */
    static final class RecordingStrategy implements SupplierAuthStrategy {

        private final AtomicReference<UUID> invalidated = new AtomicReference<>();

        @Override
        public com.positivity.supplier.internal.enums.SupplierAuthType supportedType() {
            return com.positivity.supplier.internal.enums.SupplierAuthType.BEARER;
        }

        @Override
        public void apply(HttpHeaders headers, com.positivity.supplier.internal.entity.SupplierAuthConfigEntity c) {
            // Not exercised here.
        }

        @Override
        public void invalidateCachedCredential(UUID authConfigId) {
            invalidated.set(authConfigId);
        }
    }

    @TestConfiguration
    static class WiringConfig {

        @Bean
        RecordingStrategy recordingStrategy() {
            return new RecordingStrategy();
        }

        /**
         * The real dispatcher, with a real strategy for every auth type — its constructor fails startup
         * otherwise, which is itself a guarantee worth not bypassing here.
         */
        @Bean
        SupplierAuthStrategies supplierAuthStrategies(RecordingStrategy recording) {
            SecretSchemeRegistry secrets = new SecretSchemeRegistry(
                    List.of(new com.positivity.supplier.internal.service.EnvSecretReferenceResolver()));
            return new SupplierAuthStrategies(List.of(
                    recording,
                    new BasicPlusApiKeyAuthStrategy(secrets),
                    new OAuth2ClientCredentialsAuthStrategy(
                            secrets, new SupplierHttpClients(), Clock.systemUTC(), 30, 5000, 15000)));
        }
    }

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private RecordingStrategy recordingStrategy;

    @Test
    void publishingTheEventReachesTheStrategiesThatCacheCredentials() {
        UUID authConfigId = UUID.randomUUID();

        publisher.publishEvent(new SupplierAuthConfigChanged(authConfigId, SupplierAuthConfigChanged.Change.UPDATED));

        assertThat(recordingStrategy.invalidated.get())
                .as("the @EventListener on SupplierAuthStrategies is the ONLY thing joining the admin write to"
                        + " the token cache. Without it, rotating a client secret keeps failing until the"
                        + " cached token expires -- up to an hour -- while every other test stays green")
                .isEqualTo(authConfigId);
    }

    @Test
    void aDeletionAlsoReachesThem() {
        UUID authConfigId = UUID.randomUUID();

        publisher.publishEvent(new SupplierAuthConfigChanged(authConfigId, SupplierAuthConfigChanged.Change.DELETED));

        assertThat(recordingStrategy.invalidated.get())
                .as("a token cached for an auth config that no longer exists is the most stale state there is")
                .isEqualTo(authConfigId);
    }
}
