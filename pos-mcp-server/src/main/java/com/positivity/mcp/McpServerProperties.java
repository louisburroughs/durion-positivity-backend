package com.positivity.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcp.server")
public record McpServerProperties(
        Transport transport,
        String baseUrl, // for HTTP
        String command, // for stdio
        java.util.List<String> args) {
    public enum Transport {
        STDIO, HTTP
    }
}