package com.positivity.posimage;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@OpenAPIDefinition(
    info = @Info(
        title = "Image API",
        version = "1.0",
        description = "API for managing and serving images in the POS system"
    )
)
@SpringBootApplication
public class PosImageApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosImageApplication.class, args);
    }
}
