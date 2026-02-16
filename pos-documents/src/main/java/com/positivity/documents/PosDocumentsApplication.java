package com.positivity.documents;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
@ConfigurationPropertiesScan
@OpenAPIDefinition(
        info = @Info(
                title = "POS Documents Service API",
                version = "1.0",
                description = "Document rendering service with multi-format PDF output"
        )
)
public class PosDocumentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PosDocumentsApplication.class, args);
    }
}
