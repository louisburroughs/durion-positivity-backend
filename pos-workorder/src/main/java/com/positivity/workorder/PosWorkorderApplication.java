package com.positivity.workorder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot application entry point for the Workorder service.
 *
 * Note: Jackson 3.0 ObjectMapper is auto-configured by Spring Boot 4.0.1 with
 * Java 8 date/time support built-in. No manual configuration required.
 *
 * @EnableScheduling enables support for @Scheduled tasks like
 *                   ApprovalExpirationJob.
 */
@SpringBootApplication(
        scanBasePackages = {"com.positivity.workorder"},
        exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableScheduling
public class PosWorkorderApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosWorkorderApplication.class, args);
    }
}
