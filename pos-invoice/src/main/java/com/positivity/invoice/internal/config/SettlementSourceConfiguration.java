package com.positivity.invoice.internal.config;

import com.positivity.invoice.internal.settlement.SettlementSourcePort;
import com.positivity.invoice.internal.settlement.UnavailableSettlementSourceAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the default {@link SettlementSourcePort} to the {@link UnavailableSettlementSourceAdapter}
 * placeholder until a real processor settlement adapter is provided (story F1b, issue #962), mirroring
 * {@link PaymentGatewayConfiguration}.
 */
@Configuration
public class SettlementSourceConfiguration {

    @Bean
    @ConditionalOnMissingBean(SettlementSourcePort.class)
    public SettlementSourcePort settlementSourcePort() {
        return new UnavailableSettlementSourceAdapter();
    }
}
