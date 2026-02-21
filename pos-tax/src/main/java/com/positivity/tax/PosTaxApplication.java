package com.positivity.tax;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Main application class for POS Tax Service.
 * <p>
 * This service provides tax calculation capabilities with:
 * <ul>
 *   <li>External tax service API passthrough for production</li>
 *   <li>Test mode with configurable calculation logic for development/testing</li>
 *   <li>Event emission for audit and observability</li>
 * </ul>
 */
@SpringBootApplication
@EnableDiscoveryClient
public class PosTaxApplication {

    public static void main(String[] args) {
        SpringApplication.run(PosTaxApplication.class, args);
    }
}
