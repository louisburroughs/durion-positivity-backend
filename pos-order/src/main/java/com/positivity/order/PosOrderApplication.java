package com.positivity.order;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Main application class for POS Order service.
 * 
 * Provides order management functionality including:
 * - Price override management with approval workflow
 * - Order line item operations
 * - Audit trail and compliance reporting
 */
@OpenAPIDefinition(info = @Info(title = "Order API", version = "1.0", description = "API for managing orders and price overrides in the POS system"))
@SpringBootApplication
@EnableJpaRepositories
public class PosOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(PosOrderApplication.class, args);
    }
}
