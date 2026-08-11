package com.positivity.inventory;

import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableRetry
@org.springframework.scheduling.annotation.EnableScheduling
public class PosInventoryApplication {
    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosInventoryApplication.class, args);
    }
}
