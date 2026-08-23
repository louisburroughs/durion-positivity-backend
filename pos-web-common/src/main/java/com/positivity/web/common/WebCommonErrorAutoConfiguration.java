package com.positivity.web.common;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link GlobalApiExceptionHandler} for every servlet web application that has
 * pos-web-common on its classpath — the "enforced rather than remembered" delivery mechanism
 * for issue #1471's catch-all requirement.
 *
 * <p>Opt out with {@code pos.web.global-exception-handler.enabled=false} (for example in a
 * test slice that asserts raw framework behavior). A service that defines its own
 * {@code GlobalApiExceptionHandler} bean also suppresses this one.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = Type.SERVLET)
public class WebCommonErrorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            name = "pos.web.global-exception-handler.enabled",
            havingValue = "true",
            matchIfMissing = true)
    public GlobalApiExceptionHandler globalApiExceptionHandler(ObjectProvider<Clock> clockProvider) {
        return new GlobalApiExceptionHandler(clockProvider.getIfAvailable(Clock::systemUTC));
    }
}
