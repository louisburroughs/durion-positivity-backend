package com.positivity.nhtsa;

import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class PosVehicleReferenceNhtsaApplication {
    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosVehicleReferenceNhtsaApplication.class, args);
    }

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }
}
