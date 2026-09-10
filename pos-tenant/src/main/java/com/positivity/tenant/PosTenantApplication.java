package com.positivity.tenant;

import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * Tenant registry service (ADR-0062 §7): the master {@code tenant} table and the {@code account}
 * that owns each tenancy. Runs inside the platform tenant; reachable by platform staff only.
 */
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableScheduling
public class PosTenantApplication {

    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosTenantApplication.class, args);
    }

    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
