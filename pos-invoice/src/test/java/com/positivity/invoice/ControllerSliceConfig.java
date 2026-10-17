package com.positivity.invoice;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Shared beans for {@code @WebMvcTest} controller slices in this module (issue #1723).
 *
 * <p>Slices were unusable here until #1723: {@code PosInvoiceApplication} declared
 * {@code @EnableJpaRepositories} directly, which a web slice cannot exclude, so every attempt
 * failed with {@code NoSuchBeanDefinitionException: entityManagerFactory}. The error-handling
 * tests added by #1694 were written against the module's full context instead, leaving the
 * codebase with two shapes for the same kind of test. With the annotation gone, this module uses
 * the same shape as the other twelve:
 *
 * <pre>{@code
 * @WebMvcTest(SomeController.class)
 * @Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class,
 *          ControllerSliceConfig.class})
 * }</pre>
 *
 * <p>{@code GatewaySecurityConfig} is imported rather than this module's {@code SecurityConfig}
 * because the latter also declares {@code RestClient} beans a controller slice has no use for;
 * the authentication behaviour under test lives in the shared config.
 */
@TestConfiguration
public class ControllerSliceConfig {

    /** Fixed so advice timestamps are deterministic; ADR-0013 forbids ad-hoc clock reads in tests. */
    public static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);

    @Bean
    public Clock clock() {
        return TEST_CLOCK;
    }

}
