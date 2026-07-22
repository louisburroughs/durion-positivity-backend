package com.positivity.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableCaching
@EnableScheduling
public class PosCatalogApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosCatalogApplication.class, args);
    }
}
