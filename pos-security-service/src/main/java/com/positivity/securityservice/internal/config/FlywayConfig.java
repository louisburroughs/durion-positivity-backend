package com.positivity.securityservice.internal.config;

import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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

    @Bean(initMethod = "migrate")
    @ConditionalOnMissingBean(Flyway.class)
    public Flyway mcpFlyway(
            DataSource dataSource, @Value("${SECURITY_SEED_ADMIN_PASSWORD_HASH:}") String seedAdminPasswordHash) {
        if (seedAdminPasswordHash == null || seedAdminPasswordHash.isBlank()) {
            throw new IllegalStateException("Missing required configuration: SECURITY_SEED_ADMIN_PASSWORD_HASH");
        }

        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of("seed_admin_password_hash", seedAdminPasswordHash))
                .load();
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
