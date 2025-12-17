package com.positivity.agent.impl;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.AgentPerformanceSpec;
import com.positivity.agent.BaseAgent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * Integration & Gateway Agent - Provides API Gateway integration and external
 * service patterns
 * 
 * Implements REQ-006 capabilities:
 * - REST API design, OpenAPI specification, and HTTP best practices
 * - API Gateway routing, rate limiting, and request/response transformation
 * - Authentication, authorization, and error handling patterns
 * - API versioning, backward compatibility, and contract testing
 * - Caching, response compression, and data serialization optimization
 */
@Component
public class IntegrationGatewayAgent extends BaseAgent {

    public IntegrationGatewayAgent() {
        super(
                "integration-gateway-agent",
                "Integration & Gateway Agent",
                "integration",
                Set.of("api-gateway", "rest-api", "openapi", "routing", "rate-limiting",
                        "authentication", "authorization", "versioning", "caching", "compression",
                        "error-handling", "contract-testing", "cors", "oauth2", "jwt"),
                Set.of(), // No dependencies
                new AgentPerformanceSpec(Duration.ofSeconds(3), 0.92, 0.999, 10, Duration.ofMinutes(5)));
    }

    @Override
    protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
        return AgentGuidanceResponse.success(
                request.requestId(),
                getId(),
                generateIntegrationGuidance(request),
                0.92,
                List.of("API Gateway Integration", "REST API Design", "Authentication Patterns"),
                Duration.ZERO);
    }

    private String generateIntegrationGuidance(AgentConsultationRequest request) {
        String baseGuidance = "POS System Integration & Gateway Analysis for " + request.domain() + ":\n\n";
        String query = request.query().toLowerCase();

        // API Gateway routing and configuration (prioritize over general API)
        if (query.contains("gateway") || query.contains("routing") || query.contains("load-balancing")) {
            return baseGuidance + generateGatewayRoutingGuidance(request);
        }

        // REST API design and OpenAPI specification
        if (query.contains("rest") || query.contains("openapi") || query.contains("swagger") ||
                query.contains("endpoint") || (query.contains("api") && !query.contains("gateway"))) {
            return baseGuidance + generateRestApiGuidance(request);
        }

        // Rate limiting and request/response transformation
        if (query.contains("rate-limit") || query.contains("throttling") || query.contains("transformation")) {
            return baseGuidance + generateRateLimitingGuidance(request);
        }

        // Authentication and authorization patterns
        if (query.contains("auth") || query.contains("jwt") || query.contains("oauth") || query.contains("security")) {
            return baseGuidance + generateAuthGuidance(request);
        }

        // Error handling patterns
        if (query.contains("error") || query.contains("exception") || query.contains("validation")) {
            return baseGuidance + generateErrorHandlingGuidance(request);
        }

        // API versioning and backward compatibility
        if (query.contains("version") || query.contains("compatibility") || query.contains("migration")) {
            return baseGuidance + generateVersioningGuidance(request);
        }

        // Caching and performance optimization
        if (query.contains("cache") || query.contains("performance") || query.contains("compression")) {
            return baseGuidance + generateCachingGuidance(request);
        }

        // General integration guidance
        return baseGuidance + generateGeneralIntegrationGuidance();
    }

    private String generateRestApiGuidance(AgentConsultationRequest request) {
        return "REST API Design & OpenAPI Specification:\n\n" +
                "RESOURCE NAMING PATTERNS:\n" +
                "- Use nouns for resources, verbs for actions: /api/v1/customers/{id}/orders\n" +
                "- HTTP Methods: GET (read), POST (create), PUT (update), PATCH (partial), DELETE (remove)\n" +
                "- Status Codes: 200 (OK), 201 (Created), 204 (No Content), 400 (Bad Request), 401 (Unauthorized), 404 (Not Found), 500 (Internal Error)\n\n"
                +

                "QUERY PATTERNS:\n" +
                "- Pagination: Use limit/offset or cursor-based with Link headers\n" +
                "- Filtering: Use query parameters: ?status=active&category=electronics\n" +
                "- Sorting: Use sort parameter: ?sort=name,asc&sort=created,desc\n\n" +

                "OPENAPI SPECIFICATION:\n" +
                "- Use OpenAPI 3.0 for comprehensive API documentation\n" +
                "- Include request/response examples and schema definitions\n" +
                "- Document error responses and status codes\n" +
                "- Use tags for logical grouping of endpoints\n\n" +

                "POS-SPECIFIC API PATTERNS:\n" +
                "- Customer APIs: /api/v1/customers, /api/v1/customers/{id}/orders\n" +
                "- Catalog APIs: /api/v1/products, /api/v1/categories, /api/v1/inventory\n" +
                "- Order APIs: /api/v1/orders, /api/v1/invoices, /api/v1/work-orders\n" +
                "- Vehicle APIs: /api/v1/vehicles, /api/v1/fitment, /api/v1/compatibility\n";
    }

    private String generateGatewayRoutingGuidance(AgentConsultationRequest request) {
        return "API Gateway Routing & Configuration:\n\n" +
                "ROUTING STRATEGIES:\n" +
                "- Path-based: /api/v1/catalog/** -> pos-catalog service\n" +
                "- Header-based: X-Service-Version: v2 -> pos-catalog-v2\n" +
                "- Load Balancing: Round-robin, weighted, or least-connections\n\n" +

                "RESILIENCE PATTERNS:\n" +
                "- Circuit Breaker: Implement with fallback responses\n" +
                "- Timeout Config: catalog=5s, inventory=10s, orders=15s\n" +
                "- Retry Policy: Exponential backoff with jitter (3 retries, 100ms base)\n\n" +

                "SERVICE DISCOVERY:\n" +
                "- Integrate with Eureka or Consul for dynamic service discovery\n" +
                "- Health Check Endpoints: /actuator/health\n" +
                "- Service Registration: Automatic registration with metadata\n\n" +

                "POS GATEWAY CONFIGURATION:\n" +
                "- Customer Services: pos-customer, pos-people -> /api/v1/customers/**\n" +
                "- Catalog Services: pos-catalog, pos-inventory -> /api/v1/catalog/**\n" +
                "- Order Services: pos-order, pos-invoice -> /api/v1/orders/**\n" +
                "- Vehicle Services: pos-vehicle-* -> /api/v1/vehicles/**\n";
    }

    private String generateRateLimitingGuidance(AgentConsultationRequest request) {
        return "Rate Limiting & Request/Response Transformation:\n\n" +
                "RATE LIMITING ALGORITHMS:\n" +
                "- Token Bucket: Smooth rate limiting with burst handling\n" +
                "- Sliding Window: Precise rate limiting with time windows\n\n" +

                "RATE LIMITING STRATEGIES:\n" +
                "- Per User: 1000 requests/hour for authenticated users\n" +
                "- Per IP: 100 requests/minute for anonymous users\n" +
                "- Per Endpoint: /search=10/min, /orders=100/min, /catalog=50/min\n\n" +

                "RESPONSE HANDLING:\n" +
                "- Rate Limit Headers: X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset\n" +
                "- Throttling Response: 429 Too Many Requests with Retry-After header\n\n" +

                "REQUEST/RESPONSE TRANSFORMATION:\n" +
                "- Transform request headers and body before forwarding\n" +
                "- Add correlation IDs and tracing headers\n" +
                "- Remove sensitive headers from responses\n" +
                "- Format conversion for client compatibility\n";
    }

    private String generateAuthGuidance(AgentConsultationRequest request) {
        return "Authentication & Authorization Patterns:\n\n" +
                "JWT AUTHENTICATION:\n" +
                "- Validate JWT tokens with RS256 signature verification\n" +
                "- Token expiration and refresh token handling\n" +
                "- Claims validation for user roles and permissions\n\n" +

                "OAUTH 2.0 INTEGRATION:\n" +
                "- OAuth 2.0 with PKCE for public clients\n" +
                "- Client credentials for service-to-service communication\n" +
                "- Scope validation for fine-grained permissions: read:orders, write:inventory\n\n" +

                "ROLE-BASED ACCESS CONTROL:\n" +
                "- Roles: admin, manager, cashier, customer\n" +
                "- Permission mapping per POS domain\n" +
                "- Context-aware authorization based on resource ownership\n\n" +

                "SECURITY CONFIGURATION:\n" +
                "- CORS configuration for browser-based clients\n" +
                "- CSRF protection for state-changing operations\n" +
                "- Security headers: X-Frame-Options, X-Content-Type-Options, HSTS\n" +
                "- API key authentication for external integrations\n";
    }

    private String generateErrorHandlingGuidance(AgentConsultationRequest request) {
        return "Error Handling & Response Patterns:\n\n" +
                "ERROR RESPONSE FORMAT:\n" +
                "- Consistent format: {\"error\": {\"code\": \"INVALID_INPUT\", \"message\": \"...\", \"details\": []}}\n"
                +
                "- Field-level validation errors with path and constraint information\n" +
                "- Business logic errors with domain-specific error codes\n\n" +

                "SYSTEM ERROR HANDLING:\n" +
                "- System errors with correlation IDs for troubleshooting\n" +
                "- Structured error logging with correlation IDs and context\n" +
                "- Error rate monitoring and alerting thresholds\n\n" +

                "RESILIENCE PATTERNS:\n" +
                "- Graceful degradation when downstream services unavailable\n" +
                "- Fallback responses for non-critical service failures\n" +
                "- Circuit breaker integration with error handling\n\n" +

                "POS-SPECIFIC ERROR HANDLING:\n" +
                "- Inventory errors: OUT_OF_STOCK, INVALID_QUANTITY\n" +
                "- Payment errors: PAYMENT_DECLINED, INSUFFICIENT_FUNDS\n" +
                "- Vehicle errors: INCOMPATIBLE_FITMENT, INVALID_VIN\n";
    }

    private String generateVersioningGuidance(AgentConsultationRequest request) {
        return "API Versioning & Backward Compatibility:\n\n" +
                "VERSIONING STRATEGIES:\n" +
                "- URL Versioning: /api/v1/customers, /api/v2/customers\n" +
                "- Header Versioning: Accept: application/vnd.pos.v1+json\n" +
                "- Parameter Versioning: ?version=1.0\n" +
                "- Content Negotiation: application/vnd.pos.customer.v1+json\n\n" +

                "BACKWARD COMPATIBILITY:\n" +
                "- Maintain compatibility for at least 2 major versions\n" +
                "- Deprecation warnings with sunset dates in response headers\n" +
                "- Migration guides and breaking change documentation\n\n" +

                "CONTRACT TESTING:\n" +
                "- Contract testing with Pact to ensure API compatibility\n" +
                "- Consumer-driven contract testing between services\n" +
                "- API schema validation and compatibility checks\n\n" +

                "POS VERSION MANAGEMENT:\n" +
                "- Customer API: v1 (basic), v2 (enhanced profiles)\n" +
                "- Catalog API: v1 (products), v2 (vehicle compatibility)\n" +
                "- Order API: v1 (simple), v2 (work orders), v3 (advanced workflows)\n";
    }

    private String generateCachingGuidance(AgentConsultationRequest request) {
        return "Caching & Performance Optimization:\n\n" +
                "HTTP CACHING:\n" +
                "- Cache-Control, ETag, and Last-Modified headers\n" +
                "- CDN integration for static content and API responses\n" +
                "- Conditional requests with If-None-Match and If-Modified-Since\n\n" +

                "DISTRIBUTED CACHING:\n" +
                "- Redis caching for frequently accessed data with TTL\n" +
                "- Cache invalidation strategies: TTL, event-based, manual\n" +
                "- Cache warming for critical data during deployment\n\n" +

                "PERFORMANCE OPTIMIZATION:\n" +
                "- Response compression with gzip/brotli for large payloads\n" +
                "- HTTP connection pooling for downstream services\n" +
                "- Async processing for non-critical operations\n\n" +

                "POS-SPECIFIC CACHING:\n" +
                "- Product catalog: 1 hour TTL, event-based invalidation\n" +
                "- Inventory levels: 5 minutes TTL, real-time updates\n" +
                "- Vehicle compatibility: 24 hours TTL, manual refresh\n" +
                "- Customer profiles: 30 minutes TTL, update-based invalidation\n";
    }

    private String generateGeneralIntegrationGuidance() {
        return "General Integration & Gateway Guidance:\n\n" +
                "INTEGRATION PATTERNS:\n" +
                "- API Gateway as single entry point for all client requests\n" +
                "- Service mesh for service-to-service communication\n" +
                "- Event-driven integration for loose coupling\n" +
                "- Synchronous APIs for real-time operations\n\n" +

                "MONITORING & OBSERVABILITY:\n" +
                "- Distributed tracing with correlation IDs\n" +
                "- API metrics: request rate, error rate, response time\n" +
                "- Health checks and circuit breaker monitoring\n" +
                "- Security event logging and monitoring\n\n" +

                "BEST PRACTICES:\n" +
                "- Design APIs for idempotency\n" +
                "- Implement proper timeout and retry policies\n" +
                "- Use semantic versioning for API changes\n" +
                "- Document all integration patterns and dependencies\n";
    }
}