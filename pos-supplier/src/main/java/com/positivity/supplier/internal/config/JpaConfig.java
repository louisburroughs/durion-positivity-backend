package com.positivity.supplier.internal.config;

import com.positivity.security.common.SecurityContextHelper;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA auditing configuration (ADR-0018/0024): {@code @CreatedDate}/{@code @LastModifiedDate}
 * via {@code AuditingEntityListener}, and {@code @CreatedBy}/{@code @LastModifiedBy} from the
 * security-context username (warranty convention) — unless a system actor override is active
 * ({@link AuditActorContext}, e.g. {@code system:yaml-bootstrap} during YAML reconciliation,
 * ADR-0050 §6).
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of(AuditActorContext.currentActor()
                .orElseGet(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system")));
    }
}
