package com.positivity.order;

import com.positivity.order.internal.config.JpaAuditingConfig;
import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The beans a {@code @DataJpaTest} slice of this module needs and its narrowed context does not
 * supply: a {@link Clock}, because {@link JpaAuditingConfig} — imported here so that
 * {@code @CreatedDate}/{@code @LastModifiedDate} populate the {@code NOT NULL} audit columns every
 * scoped table carries — resolves its {@code DateTimeProvider} from one.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(JpaAuditingConfig.class)
public class SliceSupportConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
