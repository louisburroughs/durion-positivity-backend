package com.positivity.securityservice.internal.config;

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
     */
    @Bean(initMethod = "migrate")
    @ConditionalOnMissingBean(Flyway.class)
    public Flyway mcpFlyway(
            DataSource dataSource,
            @Value("${SECURITY_SEED_ADMIN_PASSWORD_HASH:}") String seedAdminPasswordHash,
            @Value("${spring.flyway.url:}") String flywayUrl,
            @Value("${spring.flyway.user:}") String flywayUser,
            @Value("${spring.flyway.password:}") String flywayPassword,
            @Value("${spring.datasource.url:}") String datasourceUrl) {
        if (seedAdminPasswordHash == null || seedAdminPasswordHash.isBlank()) {
            throw new IllegalStateException("Missing required configuration: SECURITY_SEED_ADMIN_PASSWORD_HASH");
        }

        FluentConfiguration configuration = Flyway.configure()
                .locations("classpath:db/migration")
                .placeholders(Map.of("seed_admin_password_hash", seedAdminPasswordHash));
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
