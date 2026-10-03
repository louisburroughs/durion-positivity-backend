package com.positivity.platformsender;

import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling drives the outbox drain (OutboxPublisher) and the provider-outcome queue poll.
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class PosPlatformSenderApplication {
    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosPlatformSenderApplication.class, args);
    }
}
