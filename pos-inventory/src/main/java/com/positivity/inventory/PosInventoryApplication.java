package com.positivity.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class PosInventoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosInventoryApplication.class, args);
    }
}
