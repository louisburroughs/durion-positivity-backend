package com.positivity.location;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@OpenAPIDefinition(
    info = @Info(
        title = "Location API",
        version = "1.0",
        description = "API for managing locations in the POS system"
    )
)
@SpringBootApplication
public class PosLocationApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosLocationApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}

