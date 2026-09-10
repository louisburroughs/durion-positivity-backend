package com.positivity.poseventreceiver.internal.config;

import jakarta.persistence.EntityManagerFactory;
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
     * the application {@link DataSource} as before. Migration locations come from
     * {@code spring.flyway.locations}, as Boot's own auto-configuration would read them.
     */
    @Bean(initMethod = "migrate")
    @ConditionalOnMissingBean(Flyway.class)
    public Flyway eventReceiverFlyway(
            DataSource dataSource,
            @Value("${spring.flyway.url:}") String flywayUrl,
            @Value("${spring.flyway.user:}") String flywayUser,
            @Value("${spring.flyway.password:}") String flywayPassword,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${spring.flyway.locations:classpath:db/migration}") String[] locations,
            @Value("${spring.flyway.out-of-order:false}") boolean outOfOrder) {
        // Honours spring.flyway.locations, as Boot's auto-configuration would; out-of-order lets
        // V1_1 (emitted_event without row security) land on a database that already carries V2.
        FluentConfiguration configuration =
                Flyway.configure().locations(locations).outOfOrder(outOfOrder);
        if (flywayUser != null && !flywayUser.isBlank()) {
            String url = flywayUrl == null || flywayUrl.isBlank() ? datasourceUrl : flywayUrl;
            configuration = configuration.dataSource(url, flywayUser, flywayPassword);
        } else {
            configuration = configuration.dataSource(dataSource);
        }
        return configuration.load();
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
