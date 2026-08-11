package com.positivity.price;

import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class PosPriceApplication {
    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosPriceApplication.class, args);
    }
}
