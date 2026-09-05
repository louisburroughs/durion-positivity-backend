package com.positivity.invoice;

import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for the Invoice service.
 * CAP:092 - Preferences & Billing Rules
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
@EnableDiscoveryClient
@EnableAsync
@EnableScheduling
public class PosInvoiceApplication {

    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosInvoiceApplication.class, args);
    }
}
