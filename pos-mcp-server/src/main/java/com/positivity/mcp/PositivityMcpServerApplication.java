package com.positivity.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the Durion Positivity MCP server.
 * <p>
 * The actual MCP server is configured as a Spring bean so that
 * it can be accessed both by HTTP controllers and other services.
 */
@SpringBootApplication
public class PositivityMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PositivityMcpServerApplication.class, args);
    }
}

