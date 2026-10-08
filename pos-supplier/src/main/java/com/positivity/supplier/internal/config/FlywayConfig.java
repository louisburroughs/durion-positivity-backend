package com.positivity.supplier.internal.config;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.beans.factory.ObjectProvider;
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
     *
     * <p>{@link JavaMigration} beans are handed to Flyway as Boot's auto-configuration would: this bean
     * replaces that auto-configuration, so without it a bean migration such as {@code V4} (#2621, which needs
     * the vendor tax-id key a SQL script cannot hold) would be silently skipped, and the first context that
     * did see it would then fail validation with "resolved migration not applied".
     */
    @Bean(initMethod = "migrate")
    @ConditionalOnMissingBean(Flyway.class)
    public Flyway mcpFlyway(
            DataSource dataSource,
            @Value("${spring.flyway.url:}") String flywayUrl,
            @Value("${spring.flyway.user:}") String flywayUser,
            @Value("${spring.flyway.password:}") String flywayPassword,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${spring.flyway.locations:classpath:db/migration}") String[] locations,
            ObjectProvider<JavaMigration> javaMigrations) {
        // Honours spring.flyway.locations, which no profile in this module overrides today.
        FluentConfiguration configuration = Flyway.configure()
                .locations(locations)
                .javaMigrations(javaMigrations.orderedStream().toArray(JavaMigration[]::new));
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
