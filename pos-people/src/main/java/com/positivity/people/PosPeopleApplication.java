package com.positivity.people;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@OpenAPIDefinition(
    info = @Info(
        title = "People API",
        version = "1.0",
        description = "API for managing people in the POS system"
    )
)
@SpringBootApplication
public class PosPeopleApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosPeopleApplication.class, args);
    }
}

