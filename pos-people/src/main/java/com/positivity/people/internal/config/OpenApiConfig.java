package com.positivity.people.internal.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
public class OpenApiConfig {

        @Bean
        public OpenAPI customOpenAPI() {
                return new OpenAPI()
                                .info(new Info()
                                                .title("POS Human Resources Service API")
                                                .description(
                                                                "Human Resources service for employee management, payroll, and benefits administration")
                                                .version("v1")
                                                .contact(new Contact()
                                                                .email("louis.burroughs@gmail.com")
                                                                .name("Durion Team")))
                                .tags(Arrays.asList(
                                                new Tag().name("People API")
                                                                .description("Operations related to people records"),
                                                new Tag().name("People Availability API").description(
                                                                "Operations for querying people availability"),
                                                new Tag().name("People Reports API").description(
                                                                "Reporting endpoints for people and attendance data"),
                                                new Tag().name("People - TimeEntries")
                                                                .description("Time entry adjustments and related APIs"),
                                                new Tag().name("Time Entry Approval API")
                                                                .description("Approve/reject time entries (batch)"),
                                                new Tag().name("People - Exceptions")
                                                                .description("Time entry exception APIs"),
                                                new Tag().name("Work Sessions API").description(
                                                                "Operations for managing work sessions and breaks")));
        }
}
