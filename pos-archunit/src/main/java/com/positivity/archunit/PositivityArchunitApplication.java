package com.positivity.archunit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Architecture testing aggregator application for durion-positivity-backend.
 *
 * This application serves as the central testing hub for all domain modules
 * (pos-accounting, pos-inventory, pos-people, pos-workorder, etc.) and
 * enforces module boundaries using ArchUnit.
 *
 * Module boundaries are validated at compile time using ArchUnit rules
 * to ensure no unintended dependencies exist between modules.
 *
 * Responsibilities:
 * - Enforce modularity constraints via ArchUnit tests
 * - Validate internal package encapsulation across all modules
 * - Ensure service layer boundaries are respected
 * - Support integration testing of module contracts
 *
 * See: durion/docs/adr/0009-backend-domain-responsibilities-guide.adr.md
 */
@SpringBootApplication
public class PositivityArchunitApplication {

    public static void main(String[] args) {
        SpringApplication.run(PositivityArchunitApplication.class, args);
    }
}
