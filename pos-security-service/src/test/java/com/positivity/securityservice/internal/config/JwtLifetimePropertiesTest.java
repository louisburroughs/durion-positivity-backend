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
 * {@link JwtLifetimeProperties} (#2135): the record's invariants and scaling, and the binding the
 * module's {@code application.yml} gives it on a wall clock and under the {@code accelerated}
 * profile.
 */
@DisplayName("JwtLifetimeProperties — configurable token lifetimes (#2135)")
class JwtLifetimePropertiesTest {

    @Nested
    @DisplayName("record")
    class Record {

        @Test
        @DisplayName("defaults are one hour and seven days on an unscaled clock")
        void defaults() {
            JwtLifetimeProperties p = JwtLifetimeProperties.defaults();

            assertThat(p.accessTokenTtl()).isEqualTo(Duration.ofHours(1));
            assertThat(p.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
            assertThat(p.clockScale()).isEqualTo(1.0);
            assertThat(p.accessTokenSeconds()).isEqualTo(3600L);
            assertThat(p.refreshTokenSeconds()).isEqualTo(604800L);
        }

        @Test
        @DisplayName("the clock scale multiplies both lifetimes: an hour at scale 2920 is 2920 clock hours")
        void scaleMultipliesBothLifetimes() {
            JwtLifetimeProperties p = new JwtLifetimeProperties(Duration.ofHours(1), Duration.ofDays(7), 2920.0);

            assertThat(p.accessTokenSeconds()).isEqualTo(3600L * 2920);
            assertThat(p.refreshTokenSeconds()).isEqualTo(604800L * 2920);
        }

        @Test
        @DisplayName("a fractional scale rounds and never yields a zero lifetime")
        void fractionalScaleRoundsAndFloorsAtOneSecond() {
            JwtLifetimeProperties rounded =
                    new JwtLifetimeProperties(Duration.ofSeconds(10), Duration.ofSeconds(10), 0.25);
            assertThat(rounded.accessTokenSeconds()).isEqualTo(3L);

            JwtLifetimeProperties floored =
                    new JwtLifetimeProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), 0.001);
            assertThat(floored.accessTokenSeconds()).isEqualTo(1L);
            assertThat(floored.refreshTokenSeconds()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a zero or negative lifetime is rejected at binding time")
        void nonPositiveLifetimesRejected() {
            assertThatThrownBy(() -> new JwtLifetimeProperties(Duration.ZERO, Duration.ofDays(7), 1.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pos.security.jwt.access-token-ttl must be positive");
            assertThatThrownBy(() -> new JwtLifetimeProperties(Duration.ofHours(1), Duration.ofSeconds(-1), 1.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pos.security.jwt.refresh-token-ttl must be positive");
            assertThatThrownBy(() -> new JwtLifetimeProperties(null, Duration.ofDays(7), 1.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pos.security.jwt.access-token-ttl must be positive");
        }

        @Test
        @DisplayName("a zero, negative or non-finite scale is rejected at binding time")
        void nonFiniteOrNonPositiveScaleRejected() {
            for (double bad : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
                assertThatThrownBy(() -> new JwtLifetimeProperties(Duration.ofHours(1), Duration.ofDays(7), bad))
                        .as("scale %s", bad)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("pos.security.jwt.clock-scale must be a finite positive value");
            }
        }
    }

    /**
     * Binds the record through the module's real {@code application.yml}, so what is asserted is
     * the profile document that ships, not a test-local copy of it. {@link
     * ConfigDataApplicationContextInitializer} is what makes the runner read the yml and its
     * {@code on-profile} documents.
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
        @DisplayName("on a wall clock the lifetimes are an hour and seven days at scale 1")
        void wallClockDefaults() {
            runner().run(context -> {
                JwtLifetimeProperties p = context.getBean(JwtLifetimeProperties.class);
                assertThat(p.accessTokenTtl()).isEqualTo(Duration.ofHours(1));
                assertThat(p.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
                assertThat(p.clockScale()).isEqualTo(1.0);
            });
        }

        @Test
        @DisplayName("the lifetimes themselves are runtime-configurable through their environment variables")
        void lifetimesOverridable() {
            runner("POS_SECURITY_JWT_ACCESS_TOKEN_TTL=PT15M", "POS_SECURITY_JWT_REFRESH_TOKEN_TTL=P1D")
                    .run(context -> {
                        JwtLifetimeProperties p = context.getBean(JwtLifetimeProperties.class);
                        assertThat(p.accessTokenSeconds()).isEqualTo(900L);
                        assertThat(p.refreshTokenSeconds()).isEqualTo(86400L);
                    });
        }

        @Test
        @DisplayName("under the accelerated profile the scale is pos.time.accelerated.scale")
        void acceleratedProfileTakesTheClockScale() {
            runner("spring.profiles.active=accelerated", "pos.time.accelerated.scale=2920")
                    .run(context -> {
                        JwtLifetimeProperties p = context.getBean(JwtLifetimeProperties.class);
                        assertThat(p.clockScale()).isEqualTo(2920.0);
                        // An hour of wall time at scale 2920, not 1.2 real seconds.
                        assertThat(p.accessTokenSeconds()).isEqualTo(3600L * 2920);
                        assertThat(p.refreshTokenSeconds()).isEqualTo(604800L * 2920);
                    });
        }

        @Test
        @DisplayName("the profile also arrives additively, the way the compose override sets it")
        void acceleratedProfileArrivesThroughSpringProfilesInclude() {
            // SPRING_PROFILES_INCLUDE: accelerated is how deployment/alpha/docker-compose.accelerated.yml
            // turns the profile on, on top of whichever profile the environment already runs.
            runner(
                            "spring.profiles.active=alpha",
                            "spring.profiles.include=accelerated",
                            "pos.time.accelerated.scale=1460")
                    .run(context -> assertThat(
                                    context.getBean(JwtLifetimeProperties.class).clockScale())
                            .isEqualTo(1460.0));
        }

        @Test
        @DisplayName("with no scale configured the profile falls back to the clock's own default of 1000")
        void acceleratedProfileDefaultMatchesTheClock() {
            runner("spring.profiles.active=accelerated")
                    .run(context -> assertThat(
                                    context.getBean(JwtLifetimeProperties.class).clockScale())
                            .isEqualTo(1000.0));
        }

        @Test
        @DisplayName("an explicit POS_SECURITY_JWT_CLOCK_SCALE still wins under the accelerated profile")
        void explicitClockScaleWinsUnderAccelerated() {
            runner(
                            "spring.profiles.active=accelerated",
                            "pos.time.accelerated.scale=2920",
                            "POS_SECURITY_JWT_CLOCK_SCALE=1")
                    .run(context -> assertThat(
                                    context.getBean(JwtLifetimeProperties.class).clockScale())
                            .isEqualTo(1.0));
        }
    }

    @org.springframework.boot.context.properties.EnableConfigurationProperties(JwtLifetimeProperties.class)
    static class Registration {}
}
