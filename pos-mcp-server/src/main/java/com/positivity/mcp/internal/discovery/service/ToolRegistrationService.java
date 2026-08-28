package com.positivity.mcp.internal.discovery.service;

import reactor.core.publisher.Mono;

public interface ToolRegistrationService {

    Mono<Void> registerDiscoveredTools();
}
