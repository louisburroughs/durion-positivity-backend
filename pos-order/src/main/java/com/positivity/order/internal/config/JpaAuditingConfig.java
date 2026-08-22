package com.positivity.order.internal.config;

import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider", auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock));
    }

    /**
     * Supplies {@code @CreatedBy} / {@code @LastModifiedBy}. Auditing was enabled here without one,
     * so those fields stayed null and every insert into a table with a NOT NULL {@code created_by}
     * - purchase_order, sales_order, return_order - failed on the constraint. The "system" fallback
     * matches pos-inventory and pos-customer: an event-driven or scheduled write has no
     * authenticated principal but still has to name an author.
     */
    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.of(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
    }
}
