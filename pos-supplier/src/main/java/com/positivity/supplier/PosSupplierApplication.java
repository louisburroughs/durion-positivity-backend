package com.positivity.supplier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Supplier integration module (ADR-0049): single owner of all outbound supplier connectivity —
 * canonical model, vendor profiles, protocol adapters, exchange audit, and supplier-facing
 * orchestration.
 *
 * <p>{@code UserDetailsServiceAutoConfiguration} is excluded because pos-security-common (on
 * the classpath for ADR-0018 audit-actor sourcing) brings Spring Security, and this service
 * authenticates via the gateway-propagated context, never a local user store — matching the
 * platform service convention (e.g. pos-warranty).
 */
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@ConfigurationPropertiesScan
public class PosSupplierApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosSupplierApplication.class, args);
    }
}
