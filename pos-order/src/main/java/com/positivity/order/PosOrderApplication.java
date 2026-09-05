package com.positivity.order;

import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for POS Order service.
 *
 * Provides order management functionality including:
 * - Price override management with approval workflow
 * - Order line item operations
 * - Audit trail and compliance reporting
 *
 * <p>Deliberately does NOT declare {@code @EnableJpaRepositories} (issue #1723). Boot's
 * {@code JpaRepositoriesAutoConfiguration} already enables repository scanning from this
 * class's package whenever spring-data-jpa is on the classpath, so the explicit annotation was
 * redundant — and, being declared on the application class itself, it could not be excluded by
 * a {@code @WebMvcTest} slice, which then failed with
 * {@code NoSuchBeanDefinitionException: entityManagerFactory}. Controller-slice tests in this
 * module now work; do not reintroduce it here. If repository scanning ever needs explicit
 * configuration, put it on a separate {@code @Configuration} class that slice tests can exclude.
 */
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableScheduling
public class PosOrderApplication {

    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosOrderApplication.class, args);
    }
}
