package com.positivity.people;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
public class PosPeopleApplication {

    public static void main(String[] args) {
        SpringApplication.run(PosPeopleApplication.class, args);
    }

}
