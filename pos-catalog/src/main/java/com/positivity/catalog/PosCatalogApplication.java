package com.positivity.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class PosCatalogApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosCatalogApplication.class, args);
    }
}
