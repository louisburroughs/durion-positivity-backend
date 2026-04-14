package com.positivity.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Main application class for POS Order service.
 *
 * Provides order management functionality including:
 * - Price override management with approval workflow
 * - Order line item operations
 * - Audit trail and compliance reporting
 */
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableJpaRepositories
public class PosOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(PosOrderApplication.class, args);
    }
}
