package com.positivity.supplier.internal.client;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default {@link ExchangeObserver} registration: does nothing.
 *
 * <p>Guarantees the base client always has at least one observer, so it never needs a null check or
 * an "is auditing configured?" branch. Registered only when no other observer bean exists, so slice
 * 3's exchange-audit writer simply replaces it.
 */
@Configuration
public class ExchangeObserverConfig {

    /**
     * @return a no-op observer, used only when the context declares no other one
     */
    @Bean
    @ConditionalOnMissingBean(ExchangeObserver.class)
    public ExchangeObserver noOpExchangeObserver() {
        return new ExchangeObserver() {
            @Override
            public void onExchange(@NonNull ExchangeContext context) {
                // Intentionally empty: transport must work with no audit sink configured.
            }
        };
    }
}
