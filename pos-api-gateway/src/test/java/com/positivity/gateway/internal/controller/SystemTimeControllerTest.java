package com.positivity.gateway.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.positivity.time.ScaledClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;

class SystemTimeControllerTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant REAL_START = Instant.parse("2026-08-20T12:00:00Z");

    /** One year is the production gap; 999 seconds keeps the test arithmetic exact. */
    private static final Instant VIRTUAL_START = REAL_START.minusSeconds(999);

    private static final double SCALE = 1000.0;

    @Test
    void systemTime_reportsVirtualTimeAndAnchors_withoutJwtOrApiVersionHeader() {
        MutableClock baseClock = new MutableClock(REAL_START.plusMillis(500));
        WebTestClient client = clientFor(convergingClock(baseClock));

        client.get()
                .uri("/system/time")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                // 0.5 real seconds at 1000x closes 500 of the 999-second gap.
                .jsonPath("$.virtualTime")
                .isEqualTo("2026-08-20T11:51:41Z")
                .jsonPath("$.scale")
                .isEqualTo(1000.0)
                .jsonPath("$.zone")
                .isEqualTo("UTC")
                .jsonPath("$.accelerated")
                .isEqualTo(true)
                .jsonPath("$.converged")
                .isEqualTo(false)
                .jsonPath("$.realStart")
                .isEqualTo("2026-08-20T12:00:00Z")
                .jsonPath("$.virtualStart")
                .isEqualTo("2026-08-20T11:43:21Z");
    }

    @Test
    void systemTime_reportsConvergedOnceVirtualTimeReachesWallTime() {
        MutableClock baseClock = new MutableClock(REAL_START.plusSeconds(2));
        WebTestClient client = clientFor(convergingClock(baseClock));

        client.get()
                .uri("/system/time")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.converged")
                .isEqualTo(true)
                // After convergence the endpoint reports ordinary wall time.
                .jsonPath("$.virtualTime")
                .isEqualTo("2026-08-20T12:00:02Z");
    }

    @Test
    void constructor_rejectsAClockThatIsNotAccelerated() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new SystemTimeController(Clock.fixed(REAL_START, UTC)))
                .withMessageContaining("ScaledClock");
    }

    @Test
    void controller_isNotRegisteredOutsideTheAcceleratedProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(ClockConfiguration.class, SystemTimeController.class)
                .run(context -> assertThat(context).doesNotHaveBean(SystemTimeController.class));
    }

    @Test
    void controller_isRegisteredUnderTheAcceleratedProfile() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=accelerated")
                .withUserConfiguration(ClockConfiguration.class, SystemTimeController.class)
                .run(context -> assertThat(context).hasSingleBean(SystemTimeController.class));
    }

    private static WebTestClient clientFor(ScaledClock clock) {
        return WebTestClient.bindToController(new SystemTimeController(clock)).build();
    }

    private static ScaledClock convergingClock(Clock baseClock) {
        return new ScaledClock(baseClock, UTC, REAL_START, VIRTUAL_START, SCALE, true);
    }

    static class ClockConfiguration {

        @Bean
        Clock clock() {
            return new ScaledClock(Clock.systemUTC(), UTC, REAL_START, VIRTUAL_START, SCALE, true);
        }
    }

    private static final class MutableClock extends Clock {
        private final Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
