package com.positivity.accounting;

import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main Spring Boot application for POS Accounting module.
 *
 * <p>Deliberately does NOT declare an explicit {@code @ComponentScan} (issue #1723). The former
 * one named {@code com.positivity.accounting} — which {@code @SpringBootApplication} already
 * scans from this class's package — plus {@code com.positivity.events} and
 * {@code com.positivity.security.common}, whose beans arrive through their own
 * {@code AutoConfiguration.imports} and through {@code SecurityConfig}'s
 * {@code @Import(GatewaySecurityConfig.class)}, exactly as in every other module. So it added
 * nothing, while overriding {@code @WebMvcTest}'s {@code TypeExcludeFilter} and pulling the
 * whole application into what should have been a controller slice. Do not reintroduce it.
 */
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableScheduling
@EnableRetry
public class PosAccountingApplication {

    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosAccountingApplication.class, args);
    }
}
