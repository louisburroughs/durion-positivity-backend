package com.positivity.vehiclefitment;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@OpenAPIDefinition(
    info = @Info(
        title = "Vehicle Fitment API",
        version = "1.0",
        description = "API for vehicle fitment data in the POS system"
    )
)
@SpringBootApplication
public class PosVehicleFitmentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosVehicleFitmentApplication.class, args);
    }
}

