package com.positivity.mcp.internal.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties({McpServerProperties.class, LlmApiProperties.class})
public class McpServerConfiguration {

    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder(@NonNull BearerTokenRelayInterceptor interceptor) {
        return RestClient.builder().requestInterceptors(list -> list.add(interceptor));
    }

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
        // Build server with tool capability enabled; ToolBootstrapRunner adds tools
        // after
        // discovery.
        var capabilities =
                McpSchema.ServerCapabilities.builder().tools(Boolean.TRUE).build();

        return McpServer.async(transportProvider)
                .serverInfo("pos-mcp-server", "0.1.0-SNAPSHOT")
                .capabilities(capabilities)
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletSseServerTransportProvider> mcpServlet(
            @NonNull HttpServletSseServerTransportProvider transportProvider, @NonNull McpServerProperties properties) {
        var registration = new ServletRegistrationBean<>(
                transportProvider, properties.messageEndpoint(), properties.sseEndpoint());
        registration.setLoadOnStartup(1);
        registration.setName("mcpSseServlet");
        return registration;
    }

    @Bean
    public WebClient discoveryWebClient() {
        return WebClient.builder().build();
    }

    @Bean
    public RestClientCustomizer bearerTokenRelayCustomizer(@NonNull BearerTokenRelayInterceptor interceptor) {
        return builder -> builder.requestInterceptors(list -> list.add(interceptor));
    }

    @Bean(destroyMethod = "close")
    public McpSyncClient mcpSyncClient(@NonNull McpServerProperties properties) {
        var transport = HttpClientSseClientTransport.builder(properties.baseUrl())
                .sseEndpoint(properties.sseEndpoint())
                .build();

        return McpClient.sync(transport).build();
    }
}
