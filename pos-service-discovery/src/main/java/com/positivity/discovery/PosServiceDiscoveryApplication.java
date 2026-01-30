package com.positivity.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * POS Service Discovery - Eureka Server for Spring Boot 4.0.1
 * 
 * The EurekaWebConfig bean handles Spring MVC configuration needed by Eureka
 * to work with Spring Boot 4.0.1's reorganized web infrastructure.
 * 
 * SimpleDiscoveryClientAutoConfiguration is excluded because it references
 * WebServerInitializedEvent which moved in Boot 4.0.1 and causes ClassNotFoundException
 * even with our shim class.
 */
@SpringBootApplication(exclude = {
    org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClientAutoConfiguration.class
})
@EnableEurekaServer
public class PosServiceDiscoveryApplication {
    public static void main(String[] args) {
        SpringApplication.run(PosServiceDiscoveryApplication.class, args);
    }
}
