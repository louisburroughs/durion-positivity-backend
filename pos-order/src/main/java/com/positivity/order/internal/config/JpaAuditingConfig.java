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
     * so those fields stayed null and every insert of an audited entity failed on its NOT NULL
     * {@code created_by}: {@link com.positivity.order.internal.entity.PurchaseOrderEntity} and
     * {@link com.positivity.order.internal.entity.PriceOverride} are the two that depend on
     * auditing. SalesOrder and ReturnOrder carry the same NOT NULL column but populate it in their
     * services, which is why they kept working. The "system" fallback matches pos-inventory and
     * pos-customer: an event-driven or scheduled write has no authenticated principal but still
     * has to name an author.
     */
    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.of(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
    }
}
