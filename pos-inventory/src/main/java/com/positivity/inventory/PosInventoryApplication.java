package com.positivity.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.positivity.inventory.repository")
public class PosInventoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosInventoryApplication.class, args);
    }
}
