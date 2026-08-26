package com.positivity.people.internal.config;

import jakarta.persistence.EntityManagerFactory;
import java.util.Arrays;
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
     * Hand-built Flyway instance (Boot's auto-configuration backs off), so spring.flyway.*
     * properties are NOT applied automatically — each one this module relies on must be bound
     * here explicitly. ignore-migration-patterns carries "repeatable:missing" for the retired
     * timekeeping seed (docs/DATA_SEED_STRATEGY.md, #1527): without it, environments whose
     * schema history recorded the deleted seed fail startup validation with "applied migration
     * not resolved locally".
     */
    @Bean(initMethod = "migrate")
    @ConditionalOnMissingBean(Flyway.class)
    public Flyway mcpFlyway(
            DataSource dataSource,
            @Value("${spring.flyway.ignore-migration-patterns:}") String[] ignoreMigrationPatterns) {
        FluentConfiguration configuration =
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        String[] patterns = Arrays.stream(ignoreMigrationPatterns)
                .filter(pattern -> !pattern.isBlank())
                .toArray(String[]::new);
        if (patterns.length > 0) {
            configuration.ignoreMigrationPatterns(patterns);
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
