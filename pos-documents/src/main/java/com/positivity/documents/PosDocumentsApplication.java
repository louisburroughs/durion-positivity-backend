package com.positivity.documents;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class PosDocumentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PosDocumentsApplication.class, args);
    }
}
