package com.positivity.vehicle;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@OpenAPIDefinition(
    info = @Info(
        title = "Vehicle Inventory API",
        version = "1.0",
        description = "API for managing vehicle inventory in the POS system"
    )
)
@SpringBootApplication
public class PosVehicleApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosVehicleApplication.class, args);
    }
}
