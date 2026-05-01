package com.positivity.mcp;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Spring Boot entry point for the Durion Positivity MCP server.
 * <p>
 * The actual MCP server is configured as a Spring bean so that
 * it can be accessed both by HTTP controllers and other services.
 */
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@EnableConfigurationProperties(StaticRagPreloadProperties.class)
public class PositivityMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PositivityMcpServerApplication.class, args);
    }
}
