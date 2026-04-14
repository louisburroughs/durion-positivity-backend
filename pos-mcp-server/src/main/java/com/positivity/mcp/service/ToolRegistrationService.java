package com.positivity.mcp.service;

import reactor.core.publisher.Mono;

public interface ToolRegistrationService {

    Mono<Void> registerDiscoveredTools();
}
