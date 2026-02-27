package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.discovery.OpenApiDocumentFetcher;
import com.positivity.mcp.internal.discovery.OpenApiToolMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ToolRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistrationService.class);

    private final DiscoveryClient discoveryClient;
    private final OpenApiDocumentFetcher openApiDocumentFetcher;
    private final OpenApiToolMapper openApiToolMapper;
    private final McpAsyncServer mcpAsyncServer;

    public ToolRegistrationService(@NonNull DiscoveryClient discoveryClient,
                                   @NonNull OpenApiDocumentFetcher openApiDocumentFetcher,
                                   @NonNull OpenApiToolMapper openApiToolMapper,
                                   @NonNull McpAsyncServer mcpAsyncServer) {
        this.discoveryClient = discoveryClient;
        this.openApiDocumentFetcher = openApiDocumentFetcher;
        this.openApiToolMapper = openApiToolMapper;
        this.mcpAsyncServer = mcpAsyncServer;
    }

    public @NonNull Mono<Void> registerDiscoveredTools() {
        return Flux.fromIterable(discoveryClient.getServices())
                .filter(service -> !"mcp-server".equalsIgnoreCase(service))
                .flatMap(openApiDocumentFetcher::fetchForService)
                .flatMap(discovered -> Flux.fromIterable(
                        openApiToolMapper.toToolSpecifications(discovered.serviceId(), discovered.baseUri(), discovered.openApi())))
                .flatMap(mcpAsyncServer::addTool)
                .then(mcpAsyncServer.notifyToolsListChanged())
                .doOnSuccess(ignored -> log.info("Registered MCP tools for discovered services"))
                .onErrorResume(ex -> {
                    log.warn("Failed to register discovered tools: {}", ex.getMessage());
                    return Mono.empty();
                });
    }
}
