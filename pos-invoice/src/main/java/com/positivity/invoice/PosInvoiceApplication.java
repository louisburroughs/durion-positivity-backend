package com.positivity.invoice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Main application class for the Invoice service.
 * CAP:092 - Preferences & Billing Rules
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableJpaRepositories
public class PosInvoiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PosInvoiceApplication.class, args);
    }
    
}