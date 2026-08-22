package com.positivity.securityservice;

import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableScheduling
public class PosSecurityServiceApplication {
    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosSecurityServiceApplication.class, args);
    }
}
