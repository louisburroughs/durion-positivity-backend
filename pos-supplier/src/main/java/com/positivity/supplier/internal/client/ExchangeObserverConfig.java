package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.spi.ExchangeContext;
import com.positivity.supplier.internal.spi.ExchangeObserver;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default {@link ExchangeObserver} registration: does nothing.
 *
 * <p>Guarantees the base client always has at least one observer, so it never needs a null check or an
 * "is auditing configured?" branch. It matters most for slice tests that load {@code internal.client}
 * without the service package.
 *
 * <p>Registered <strong>unconditionally</strong>. It previously carried
 * {@code @ConditionalOnMissingBean(ExchangeObserver.class)}, which Spring documents as unreliable outside
 * auto-configuration: the condition sees only the beans registered so far, and for two component-scanned
 * classes that is scan ordering. It worked, but "which observer receives every vendor payload" is not
 * something to leave to classpath iteration order. {@code ExchangeAuditObserver} is {@code @Primary}
 * instead, so precedence is declared rather than raced for.
 */
@Configuration
public class ExchangeObserverConfig {

    /**
     * @return a no-op observer; loses to {@code ExchangeAuditObserver}'s {@code @Primary} when both exist
     */
    @Bean
    public ExchangeObserver noOpExchangeObserver() {
        return new ExchangeObserver() {
            @Override
            public void onExchange(@NonNull ExchangeContext context) {
                // Intentionally empty: transport must work with no audit sink configured.
            }
        };
    }
}
