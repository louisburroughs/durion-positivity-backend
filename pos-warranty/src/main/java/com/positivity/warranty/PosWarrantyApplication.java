package com.positivity.warranty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling drives the transactional-outbox drain (OutboxPublisher, ADR-0044 §4).
@EnableScheduling
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class PosWarrantyApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosWarrantyApplication.class, args);
    }
}
