package com.positivity.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class PosCustomerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosCustomerApplication.class, args);
    }
}
