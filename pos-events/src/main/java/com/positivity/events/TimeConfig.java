package com.positivity.events;

import com.positivity.time.ScaledClock;
import com.positivity.time.TimeSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

@AutoConfiguration
public class TimeConfig {

    @Bean
    @Profile("accelerated")
    @ConditionalOnMissingBean(Clock.class)
    public Clock acceleratedClock(
            @Value("${pos.time.accelerated.scale:1000.0}") double scale,
            @Value("${pos.time.accelerated.zone:UTC}") String zone) {
        Clock baseClock = Clock.systemUTC();
        Instant now = baseClock.instant();
        return new ScaledClock(baseClock, ZoneId.of(zone), now, now, scale);
    }

    @Bean
    @Profile("!accelerated")
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnBean(Clock.class)
    public TimeSourceBinder timeSourceBinder(Clock clock) {
        return new TimeSourceBinder(clock);
    }

    public static final class TimeSourceBinder implements SmartInitializingSingleton, DisposableBean {

        private final Clock clock;

        private TimeSourceBinder(Clock clock) {
            this.clock = clock;
        }

        @Override
        public void afterSingletonsInstantiated() {
            TimeSource.setClock(clock);
        }

        @Override
        public void destroy() {
            TimeSource.reset();
        }
    }
}
