package com.positivity.shopManager;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@OpenAPIDefinition(
    info = @Info(
        title = "Shop Manager API",
        version = "1.0",
        description = "API for shop management in the POS system"
    )
)
@SpringBootApplication
public class PosShopmanagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosShopmanagerApplication.class, args);
    }
}
