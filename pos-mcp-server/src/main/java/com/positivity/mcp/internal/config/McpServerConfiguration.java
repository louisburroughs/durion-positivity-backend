package com.positivity.mcp.internal.config;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties({McpServerProperties.class, LlmApiProperties.class})
public class McpServerConfiguration {

    @Bean
    public HttpServletSseServerTransportProvider transportProvider(@NonNull McpServerProperties properties) {
        return HttpServletSseServerTransportProvider.builder()
                .baseUrl(properties.baseUrl())
                .messageEndpoint(properties.messageEndpoint())
                .sseEndpoint(properties.sseEndpoint())
                .build();
    }

    @Bean
    public McpAsyncServer mcpAsyncServer(@NonNull HttpServletSseServerTransportProvider transportProvider) {
        // Build server with no tools; ToolBootstrapRunner adds them after discovery.
        return McpServer.async(transportProvider)
                .serverInfo("pos-mcp-server", "0.1.0-SNAPSHOT")
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletSseServerTransportProvider> mcpServlet(
            @NonNull HttpServletSseServerTransportProvider transportProvider,
            @NonNull McpServerProperties properties) {
        var registration = new ServletRegistrationBean<>(transportProvider,
                properties.messageEndpoint(),
                properties.sseEndpoint());
        registration.setLoadOnStartup(1);
        registration.setName("mcpSseServlet");
        return registration;
    }

    @Bean
    public WebClient discoveryWebClient(WebClient.Builder builder) {
        return builder.build();
    }
}
