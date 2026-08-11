package com.positivity.vehiclereferencecarapi;

import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class PosVehicleReferenceCarapiApplication {
    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosVehicleReferenceCarapiApplication.class, args);
    }

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }
}
