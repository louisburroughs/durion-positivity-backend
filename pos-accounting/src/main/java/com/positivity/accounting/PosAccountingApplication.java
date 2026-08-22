package com.positivity.accounting;

import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main Spring Boot application for POS Accounting module.
 */
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableScheduling
@EnableRetry
@ComponentScan(basePackages = {"com.positivity.accounting", "com.positivity.events", "com.positivity.security.common"})
public class PosAccountingApplication {

    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosAccountingApplication.class, args);
    }
}
