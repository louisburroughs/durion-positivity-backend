package com.positivity.customer;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@OpenAPIDefinition(
    info = @Info(
        title = "Customer API",
        version = "1.0",
        description = "API for managing customers in the POS system"
    )
)
@SpringBootApplication
public class PosCustomerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosCustomerApplication.class, args);
    }
}

