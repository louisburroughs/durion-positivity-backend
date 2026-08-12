package com.positivity.supplier.internal.config;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.supplier.internal.audit.AuditActorContext;
import com.positivity.supplier.internal.entity.AuditPayloadCipher;
import com.positivity.supplier.internal.entity.EncryptedPayloadConverter;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA auditing configuration (ADR-0018/0024): {@code @CreatedDate}/{@code @LastModifiedDate}
 * via {@code AuditingEntityListener}, and {@code @CreatedBy}/{@code @LastModifiedBy} from the
 * security-context username (warranty convention) — unless a system actor override is active
 * ({@link AuditActorContext}, e.g. {@code system:yaml-bootstrap} during YAML reconciliation,
 * ADR-0050 §6).
 *
 * <p>Also registers {@link AuditPayloadCipher} and {@link EncryptedPayloadConverter}. They belong here
 * rather than relying on component scanning because Hibernate instantiates the converter for
 * <em>any</em> persistence unit that has {@code ExchangeAuditEntity} on its classpath — including every
 * {@code @DataJpaTest} slice, which scans no {@code @Component}s. Without them, Hibernate resolves the
 * converter through {@code SpringBeanContainer}, finds no {@code AuditPayloadCipher}, and the entity
 * manager fails to build, breaking slices that have nothing to do with auditing. Importing
 * {@code JpaConfig} — which every slice already does — is therefore the correct single place for them.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@Import({AuditPayloadCipher.class, EncryptedPayloadConverter.class})
public class JpaConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of(AuditActorContext.currentActor()
                .orElseGet(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system")));
    }
}
