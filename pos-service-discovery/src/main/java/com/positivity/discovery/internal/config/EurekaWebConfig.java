package com.positivity.discovery.internal.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Boot 4.0.1 Web Configuration for Eureka
 * 
 * Provides WebMvc configuration that Netflix Eureka requires.
 * This satisfies Eureka's internal wiring while working with Boot 4.0.1's
 * reorganized web infrastructure.
 */
@Configuration
public class EurekaWebConfig implements WebMvcConfigurer {
    // Default implementations from WebMvcConfigurer satisfy Eureka's needs
}
