package com.positivity.people;

import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableScheduling
public class PosPeopleApplication {

    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosPeopleApplication.class, args);
    }
}
