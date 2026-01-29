package com.positivity.modulith;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;

/**
 * Spring Modulith aggregator application for durion-positivity-backend.
 * 
 * This application serves as the central entry point for all domain modules
 * (pos-accounting, pos-inventory, pos-people, pos-workorder, etc.) and
 * enforces module boundaries using Spring Modulith.
 * 
 * Module boundaries are validated at startup and during integration tests
 * to ensure no unintended dependencies exist between modules.
 * 
 * Responsibilities:
 * - Enforce modularity constraints via ApplicationModuleDetection
 * - Coordinate inter-module functional logging via events (pos-events/pos-event-receiver)
 * - Enable centralized cross-cutting concerns (security, logging, tracing)
 * - Support integration testing of module contracts
 * 
 * See: durion/docs/adr/0009-backend-domain-responsibilities-guide.adr.md
 */
@SpringBootApplication
@Modulith(publishesEvents = true)
public class PositivityModulithApplication {

	public static void main(String[] args) {
		SpringApplication.run(PositivityModulithApplication.class, args);
	}

}
