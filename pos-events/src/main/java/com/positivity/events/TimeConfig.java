package com.positivity.events;

import com.positivity.time.AcceleratedTimeProperties;
import com.positivity.time.ScaledClock;
import com.positivity.time.TimeSource;
import java.time.Clock;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@AutoConfiguration
public class TimeConfig {

    @Bean
    @Profile("!accelerated")
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public TimeSourceBinder timeSourceBinder(Clock clock) {
        return new TimeSourceBinder(clock);
    }

    /**
     * Supplies the single converging {@link ScaledClock} shared by every accelerated JVM.
     *
     * <p>The bean deliberately carries no {@code @ConditionalOnMissingBean}: a module that declares
     * its own {@link Clock} must fail startup with an ambiguous-dependency error rather than
     * silently disabling acceleration for that module and dragging its JPA auditing provider onto
     * wall time.
     */
    @Configuration(proxyBeanMethods = false)
    @Profile("accelerated")
    @EnableConfigurationProperties(AcceleratedTimeProperties.class)
    public static class AcceleratedTimeConfiguration {

        @Bean
        public ScaledClock acceleratedClock(AcceleratedTimeProperties properties) {
            return new ScaledClock(
                    Clock.systemUTC(),
                    properties.zone(),
                    properties.realStart(),
                    properties.virtualStart(),
                    properties.scale(),
                    properties.converge());
        }
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
