package com.positivity.vehicle.internal.config;

import com.positivity.security.common.SecurityContextHelper;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Configuration for JPA auditing to support @CreatedDate, @LastModifiedDate,
 * @CreatedBy, and @LastModifiedBy.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
    }
}
