package com.positivity.accounting.internal.config;

import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AbstractDependsOnBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(Flyway.class)
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

    /**
     * Flyway runs on the owner credential when {@code spring.flyway.user} is set (ADR-0062 §3: the
     * application pool is the non-owner {@code pos_app} role, which cannot run DDL), otherwise on
     * the application {@link DataSource} as before.
     *
     * <p>{@code ${ledger_currency}} is the ledger currency ({@link LedgerCurrency}, #2577): V15 stamps the register
     * floats that predate it with that currency, never an implicit one (ADR-0067 R-2). A versioned migration's
     * checksum is taken before placeholders are replaced, so a later change of the value does not fail validation.
     */
    @Bean(initMethod = "migrate")
    @ConditionalOnMissingBean(Flyway.class)
    public Flyway mcpFlyway(
            DataSource dataSource,
            @Value("${spring.flyway.url:}") String flywayUrl,
            @Value("${spring.flyway.user:}") String flywayUser,
            @Value("${spring.flyway.password:}") String flywayPassword,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            LedgerCurrency ledgerCurrency) {
        FluentConfiguration configuration =
                Flyway.configure().locations("classpath:db/migration").placeholders(placeholders(ledgerCurrency));
        if (flywayUser != null && !flywayUser.isBlank()) {
            String url = flywayUrl == null || flywayUrl.isBlank() ? datasourceUrl : flywayUrl;
            configuration = configuration.dataSource(url, flywayUser, flywayPassword);
        } else {
            configuration = configuration.dataSource(dataSource);
        }
        return configuration.load();
    }

    /** The placeholders the migrations read: {@code ledger_currency} (V15, #2577). */
    public static Map<String, String> placeholders(LedgerCurrency ledgerCurrency) {
        return Map.of("ledger_currency", ledgerCurrency.code());
    }

    @Bean
    public static FlywayEntityManagerFactoryDependsOnPostProcessor flywayEntityManagerFactoryDependsOnPostProcessor() {
        return new FlywayEntityManagerFactoryDependsOnPostProcessor();
    }

    static class FlywayEntityManagerFactoryDependsOnPostProcessor extends AbstractDependsOnBeanFactoryPostProcessor {

        FlywayEntityManagerFactoryDependsOnPostProcessor() {
            super(EntityManagerFactory.class, Flyway.class);
        }
    }
}
