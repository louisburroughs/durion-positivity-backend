# Inter-Service Communication Guide

This document explains how microservices in the Durion POS backend should communicate with each other through the **API Gateway** instead of making direct connections.

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        External Clients / Moqui Frontend                      │
│                                                                              │
│  Request: GET /inventory/items                                               │
│  Header: X-API-Version: 1                                                    │
└────────────────────────────────────────────────┬─────────────────────────────┘
                                                 │
                                                 ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                            API Gateway (port 8080)                           │
│                                                                              │
│  1. ApiVersionHeaderToPathFilter: /inventory/items -> /v1/inventory/items    │
│  2. Discovery Locator: Routes /v1/inventory/** -> lb://inventory (Eureka)    │
│  3. RewritePath: Strips prefix, service receives /items                      │
└────────────────────────────────────────────────┬─────────────────────────────┘
                                                 │
                     ┌───────────────────────────┼───────────────────────────┐
                     │                           │                           │
                     ▼                           ▼                           ▼
            ┌─────────────┐             ┌─────────────┐             ┌─────────────┐
            │  inventory  │             │  customer   │             │  security   │
            │  (Eureka)   │◄───────────►│  (Eureka)   │◄───────────►│  (Eureka)   │
            └─────────────┘             └─────────────┘             └─────────────┘
```

## Two Communication Strategies

### Strategy 1: Through API Gateway (Recommended for Cross-Domain)

Services call each other **through the gateway** at `http://localhost:8080` (or configured gateway URL). This approach:

- ✅ Centralizes authentication/authorization
- ✅ Maintains API versioning consistency
- ✅ Provides single point for observability/tracing
- ✅ Simplifies network configuration (one endpoint)
- ✅ Works with dynamic port assignment (services use `server.port: 0`)

**Configuration Pattern:**

```yaml
# application.yml
gateway:
  url: ${GATEWAY_URL:http://localhost:8080}

# Service-specific shortcuts (optional)
services:
  security: ${gateway.url}/security-service
  customer: ${gateway.url}/customer
  inventory: ${gateway.url}/inventory
```

**Java Client Pattern:**

```java
@Configuration
public class ServiceClientConfig {

    @Value("${gateway.url:http://localhost:8080}")
    private String gatewayUrl;

    @Bean
    public RestClient gatewayRestClient() {
        return RestClient.builder()
                .baseUrl(gatewayUrl)
                .defaultHeader("X-API-Version", "1")  // Required by gateway
                .build();
    }
}
```

**Usage Example (CrmPermissionInitializer.java):**

```java
@Slf4j
@Configuration
public class CrmPermissionInitializer {

    @Value("${gateway.url:http://localhost:8080}")
    private String gatewayUrl;

    @Bean
    public ApplicationRunner registerCrmPermissions(RestClient gatewayRestClient) {
        return args -> {
            try {
                log.info("Starting CRM permission registration...");

                var request = CrmPermissionRegistry.buildCrmPermissionRegistration();

                // Route: /security-service/v1/permissions/register
                // Gateway rewrites to: /v1/security-service/v1/permissions/register
                // Then routes to: lb://security-service with path /v1/permissions/register
                String endpoint = "/security-service/v1/permissions/register";

                var response = gatewayRestClient
                        .post()
                        .uri(endpoint)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toEntity(Object.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    log.info("✓ CRM permissions registered successfully");
                }
            } catch (Exception e) {
                log.warn("⚠ Failed to register CRM permissions (non-blocking): {}", e.getMessage());
            }
        };
    }
}
```

### Strategy 2: Direct Eureka Load-Balanced Calls (For High-Performance Internal)

For performance-critical internal calls where gateway overhead matters, use Spring Cloud's `@LoadBalanced` RestClient/WebClient to call services directly via Eureka discovery.

**Add dependency (if not present):**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

**Configuration:**

```java
@Configuration
public class LoadBalancedClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient securityServiceClient(@LoadBalanced RestClient.Builder builder) {
        return builder
                .baseUrl("http://security-service")  // Eureka service name
                .build();
    }
}
```

**Usage:**

```java
@Service
public class PermissionService {
    
    private final RestClient securityServiceClient;

    public PermissionService(@Qualifier("securityServiceClient") RestClient client) {
        this.securityServiceClient = client;
    }

    public void registerPermissions(PermissionRequest request) {
        securityServiceClient
            .post()
            .uri("/v1/permissions/register")
            .body(request)
            .retrieve()
            .toEntity(Void.class);
    }
}
```

## Service Name Registry

Use these **Eureka service names** when routing through the gateway or load balancer:

| Module | Eureka Service Name | Gateway Path |
|--------|---------------------|--------------|
| pos-accounting | `accounting` | `/accounting/**` |
| pos-catalog | `catalog` | `/catalog/**` |
| pos-customer | `customer` | `/customer/**` |
| pos-event-receiver | `event-receiver` | `/event-receiver/**` |
| pos-image | `image` | `/image/**` |
| pos-inquiry | `inquiry` | `/inquiry/**` |
| pos-inventory | `inventory` | `/inventory/**` |
| pos-invoice | `invoice` | `/invoice/**` |
| pos-location | `location` | `/location/**` |
| pos-order | `order` | `/order/**` |
| pos-people | `people` | `/people/**` |
| pos-price | `price` | `/price/**` |
| pos-security-service | `security-service` | `/security-service/**` |
| pos-shop-manager | `shop-manager` | `/shop-manager/**` |
| pos-workorder | `workorder` | `/workorder/**` |
| pos-vehicle-fitment | `vehicle-fitment` | `/vehicle-fitment/**` |
| pos-vehicle-inventory | `vehicle-inventory` | `/vehicle-inventory/**` |

## Anti-Patterns to Avoid

### ❌ Hardcoded URLs with Ports

```java
// DON'T DO THIS - breaks with dynamic ports
@Value("${security.service.url:http://localhost:8086}")
private String securityServiceUrl;
```

### ❌ Environment Variables for Each Service

```java
// DON'T DO THIS - doesn't scale, requires per-service config
String url = System.getenv("SECURITY_SERVICE_URL");
if (url == null) {
    url = "http://localhost:8086";
}
```

### ✅ Recommended: Gateway URL Configuration

```java
// DO THIS - single gateway URL, gateway handles routing
@Value("${gateway.url:http://localhost:8080}")
private String gatewayUrl;

// Call: gatewayUrl + "/security-service/v1/permissions/register"
```

## Gateway Routing Details

The API Gateway uses Eureka Discovery Locator to automatically route requests:

1. **Client sends**: `GET /inventory/items` with header `X-API-Version: 1`
2. **ApiVersionHeaderToPathFilter rewrites**: `/inventory/items` → `/v1/inventory/items`
3. **Discovery Locator matches**: `/v1/inventory/**` → `lb://inventory` (Eureka lookup)
4. **Gateway strips prefix**: Service receives `/items`

**Key Gateway Config (application.yml):**

```yaml
spring:
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true                    # Auto-route from Eureka
          lower-case-service-id: true      # Service IDs are lowercase
```

## Example: Refactoring CrmPermissionInitializer

**Before (Direct Connection):**

```java
String securityServiceUrl = System.getenv("SECURITY_SERVICE_URL");
if (securityServiceUrl == null) {
    securityServiceUrl = "http://localhost:8086";
}
String endpoint = securityServiceUrl + "/v1/permissions/register";
```

**After (Gateway-Based):**

```java
@Value("${gateway.url:http://localhost:8080}")
private String gatewayUrl;

// Use gateway path with X-API-Version header
String endpoint = gatewayUrl + "/security-service/v1/permissions/register";

restClient
    .post()
    .uri(endpoint)
    .header("X-API-Version", "1")
    .body(request)
    .retrieve();
```

## Configuration for Different Environments

### Local Development (docker-compose)

```yaml
# docker-compose.yml
services:
  pos-customer:
    environment:
      - GATEWAY_URL=http://pos-api-gateway:8080
      - EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://pos-service-discovery:8761/eureka/
```

### Kubernetes / Production

```yaml
# ConfigMap
gateway:
  url: http://pos-api-gateway.default.svc.cluster.local:8080

eureka:
  client:
    service-url:
      defaultZone: http://pos-service-discovery.default.svc.cluster.local:8761/eureka/
```

## Testing Inter-Service Communication

### Verify Gateway Routing

```bash
# Test direct to gateway
curl -H "X-API-Version: 1" http://localhost:8080/security-service/v1/auth/health

# Check Eureka registry
curl http://localhost:8761/eureka/apps
```

### Verify Service Registration

```bash
# List all registered services
curl http://localhost:8761/eureka/apps | grep "<name>"
```

## Summary

| Scenario | Strategy | Configuration |
|----------|----------|---------------|
| Cross-domain calls | **Gateway** | `${gateway.url}/service-name/path` |
| High-frequency internal | Load-balanced | `lb://service-name/path` |
| External clients | **Gateway** | `http://gateway:8080/path` with `X-API-Version` |
| Startup registration | **Gateway** | Non-blocking, with retry logic |

**Default Recommendation**: Use the Gateway for all inter-service communication unless proven performance bottleneck requires direct load-balanced calls.
