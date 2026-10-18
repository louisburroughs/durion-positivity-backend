package com.positivity.inboundmail;

import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling drives the staging purge (StagingPurgeJob); Part B adds the receive poll and the outbox drain.
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class PosInboundMailApplication {
    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosInboundMailApplication.class, args);
    }
}
