package com.positivity.security.common;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link LocationScopeDeniedExceptionHandler} for every servlet application on the
 * pos-security-common classpath (#1870), the same "enforced rather than remembered" delivery
 * pos-web-common uses for its catch-all. A module that defines its own bean of the type
 * suppresses this one.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = Type.SERVLET)
public class LocationScopeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LocationScopeDeniedExceptionHandler locationScopeDeniedExceptionHandler(
            ObjectProvider<Clock> clockProvider) {
        return new LocationScopeDeniedExceptionHandler(clockProvider.getIfAvailable(Clock::systemUTC));
    }
}
