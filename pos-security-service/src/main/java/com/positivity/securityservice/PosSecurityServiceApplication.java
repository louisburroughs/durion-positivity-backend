package com.positivity.securityservice;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@OpenAPIDefinition(
    info = @Info(
        title = "Security Service API",
        version = "1.0",
        description = "API for security and authentication in the POS system"
    )
)
@SpringBootApplication
public class PosSecurityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosSecurityServiceApplication.class, args);
    }
}

