package com.positivity.securityservice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link JwtLifetimeProperties} (#2135): the record's invariants and the binding the module's
 * {@code application.yml} gives it. What the lifetimes mean once minted — projected through the
 * clock rather than added to it — is {@code JwtServiceImplLifetimeTest}.
 */
@DisplayName("JwtLifetimeProperties — configurable token lifetimes (#2135)")
class JwtLifetimePropertiesTest {

    @Nested
    @DisplayName("record")
    class Record {

        @Test
        @DisplayName("defaults are one hour and seven days of wall time")
        void defaults() {
            JwtLifetimeProperties p = JwtLifetimeProperties.defaults();

            assertThat(p.accessTokenTtl()).isEqualTo(Duration.ofHours(1));
            assertThat(p.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
        }

        @Test
        @DisplayName("the Redis revocation TTLs are the lifetimes in wall seconds")
        void revocationSecondsAreWallSeconds() {
            JwtLifetimeProperties p = JwtLifetimeProperties.defaults();

            assertThat(p.accessTokenRevocationSeconds()).isEqualTo(3600L);
            assertThat(p.refreshTokenRevocationSeconds()).isEqualTo(604800L);
        }

        @Test
        @DisplayName("a sub-second lifetime still yields a positive revocation TTL")
        void revocationSecondsFlooredAtOne() {
            JwtLifetimeProperties p = new JwtLifetimeProperties(Duration.ofMillis(200), Duration.ofMillis(200));

            // TokenRevocationManager rejects a non-positive TTL, and 0.2s truncates to 0 seconds.
            assertThat(p.accessTokenRevocationSeconds()).isEqualTo(1L);
            assertThat(p.refreshTokenRevocationSeconds()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a zero, negative or missing lifetime is rejected at binding time")
        void nonPositiveLifetimesRejected() {
            assertThatThrownBy(() -> new JwtLifetimeProperties(Duration.ZERO, Duration.ofDays(7)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pos.security.jwt.access-token-ttl must be positive");
            assertThatThrownBy(() -> new JwtLifetimeProperties(Duration.ofHours(1), Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pos.security.jwt.refresh-token-ttl must be positive");
            assertThatThrownBy(() -> new JwtLifetimeProperties(null, Duration.ofDays(7)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pos.security.jwt.access-token-ttl must be positive");
        }
    }

    /**
     * Binds the record through the module's real {@code application.yml}, so what is asserted is
     * the configuration that ships, not a test-local copy of it. {@link
     * ConfigDataApplicationContextInitializer} is what makes the runner read the yml at all.
     */
    @Nested
    @DisplayName("binding from application.yml")
    class Binding {

        private ApplicationContextRunner runner(String... properties) {
            return new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(Registration.class)
                    .withPropertyValues(properties);
        }

        @Test
        @DisplayName("the shipped defaults are an hour and seven days")
        void shippedDefaults() {
            runner().run(context -> {
                JwtLifetimeProperties p = context.getBean(JwtLifetimeProperties.class);
                assertThat(p.accessTokenTtl()).isEqualTo(Duration.ofHours(1));
                assertThat(p.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
            });
        }

        @Test
        @DisplayName("both lifetimes are runtime-configurable through their environment variables")
        void lifetimesOverridable() {
            runner("POS_SECURITY_JWT_ACCESS_TOKEN_TTL=PT15M", "POS_SECURITY_JWT_REFRESH_TOKEN_TTL=P1D")
                    .run(context -> {
                        JwtLifetimeProperties p = context.getBean(JwtLifetimeProperties.class);
                        assertThat(p.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
                        assertThat(p.refreshTokenTtl()).isEqualTo(Duration.ofDays(1));
                    });
        }

        @Test
        @DisplayName("the accelerated profile changes nothing here: the lifetimes are wall time on every profile")
        void acceleratedProfileLeavesTheLifetimesAlone() {
            // The clock carries the acceleration; the configuration does not have to know about it.
            runner("spring.profiles.active=alpha", "spring.profiles.include=accelerated")
                    .run(context -> {
                        JwtLifetimeProperties p = context.getBean(JwtLifetimeProperties.class);
                        assertThat(context.getEnvironment().getActiveProfiles()).contains("accelerated");
                        assertThat(p.accessTokenTtl()).isEqualTo(Duration.ofHours(1));
                        assertThat(p.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
                    });
        }

        @Test
        @DisplayName("a misconfigured lifetime fails startup rather than minting a bad token")
        void invalidLifetimeFailsStartup() {
            runner("POS_SECURITY_JWT_ACCESS_TOKEN_TTL=PT0S")
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @org.springframework.boot.context.properties.EnableConfigurationProperties(JwtLifetimeProperties.class)
    static class Registration {}
}
