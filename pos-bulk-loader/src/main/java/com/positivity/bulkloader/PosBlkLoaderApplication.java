package com.positivity.bulkloader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class PosBlkLoaderApplication {

    public static void main(String[] args) {
        SpringApplication.run(PosBlkLoaderApplication.class, args);
    }
}
