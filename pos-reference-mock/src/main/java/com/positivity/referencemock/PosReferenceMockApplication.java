package com.positivity.referencemock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Fake external labor-guide vendor for the service-time sourcing pipeline
 * (pos-catalog/docs/service-time-sourcing-plan.md §10, issue #1569 Phase 1).
 *
 * <p>This application simulates a third-party vendor that already speaks the Durion-normalized
 * provider contract, so the whole labor-time pipeline — SPI, ingestion, storage, transport,
 * estimate defaulting, variance — can be built and demonstrated end-to-end before any licensing
 * spend. It is deliberately OUTSIDE the platform mesh: no Eureka registration, no gateway route,
 * no JWT/security, no database, no Kafka. It is reached only via adapter base-url configuration
 * (the pos-supplier sandbox-override pattern) on fixed port 8095, and it serves deterministic,
 * checked-in JSON fixtures.
 */
@SpringBootApplication
public class PosReferenceMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(PosReferenceMockApplication.class, args);
    }
}
