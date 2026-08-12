package com.positivity.supplier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Supplier integration module (ADR-0049): single owner of all outbound supplier connectivity —
 * canonical model, vendor profiles, protocol adapters, exchange audit, and supplier-facing
 * orchestration.
 *
 * <p>{@code UserDetailsServiceAutoConfiguration} is excluded because pos-security-common (on
 * the classpath for ADR-0018 audit-actor sourcing) brings Spring Security, and this service
 * authenticates via the gateway-propagated context, never a local user store — matching the
 * platform service convention (e.g. pos-warranty).
 *
 * <p>{@code @EnableScheduling} drives the exchange-audit retention purge (ADR-0050 §7) and the
 * per-binding schedule coordinator (binding decision 4), following the pos-warranty convention of
 * enabling scheduling at the application class.
 */
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@ConfigurationPropertiesScan
@EnableScheduling
public class PosSupplierApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosSupplierApplication.class, args);
    }
}
