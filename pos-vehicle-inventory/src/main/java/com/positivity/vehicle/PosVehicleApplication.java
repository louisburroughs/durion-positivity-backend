package com.positivity.vehicle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling drives the outbox drain and reconciliation-manifest publishers (ADR-0044 §4).
@EnableScheduling
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class PosVehicleApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosVehicleApplication.class, args);
    }
}
