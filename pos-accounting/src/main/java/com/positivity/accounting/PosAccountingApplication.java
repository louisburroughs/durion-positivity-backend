package com.positivity.accounting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
@ComponentScan(basePackages = {"com.positivity.accounting", "com.positivity.events"})
public class PosAccountingApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosAccountingApplication.class, args);
    }
}
