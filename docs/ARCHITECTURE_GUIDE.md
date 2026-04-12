# Architecture & Infrastructure Guide

This document covers the architectural patterns, Docker configuration, service communication, and observability infrastructure for the durion-positivity-backend microservices platform.

## Table of Contents

1. [Port Strategy](#port-strategy)
2. [Docker Configuration](#docker-configuration)
3. [Inter-Service Communication](#inter-service-communication)
4. [Observability](#observability)
5. [Correlation ID Implementation](#correlation-id-implementation)
6. [PostgreSQL Setup](#postgresql-setup)

---

## Port Strategy

### Overview

The backend implements a **dynamic port strategy** for development flexibility and production stability:

- **Gateway (pos-api-gateway)**: Fixed port **8080** (single external entry point)
- **Eureka (pos-service-discovery)**: Fixed port **8761** (service discovery)
- **All other services**: Dynamic ports (`server.port: 0`) — OS assigns ephemeral ports

### Benefits

- No port conflicts on dev machines or CI/CD parallel runs
- Services auto-discover each other via Eureka
- Multiple instances of the same service can run simultaneously

### Fixed Ports

| Component | Port | Environment | Notes |
| ----------- | ------ | ------------- | ------- |
| API Gateway | 8080 | All | Single external entry point |
| Eureka Server | 8761 | Dev/local | Service registry |
| Management (Actuator) | Internal-only | Prod | Health, metrics, prometheus |

### Dynamic Ports (Ephemeral)

All downstream services use `server.port: 0`:

- pos-catalog, pos-customer, pos-inventory, pos-order, pos-accounting
- pos-workorder, pos-shop-manager, pos-location, pos-people
- pos-vehicle-*, pos-price, pos-invoice, pos-inquiry, pos-event-receiver

**How it works:**

1. Service starts with `server.port: 0`
2. OS assigns available ephemeral port
3. Service registers with Eureka using actual assigned port
4. Gateway discovers service via Eureka (`lb://SERVICE_NAME`)

### Profile Configuration

#### Local Development (`application-dev.yml`)

```yaml
server:
  port: 0

management:
  server:
    port: 0
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus

eureka:
  client:
    fetch-registry: true
    register-with-eureka: true
  instance:
    prefer-ip-address: true
    instance-id: ${spring.application.name}:${random.uuid}
```

#### Production (`application-prod.yml`)

```yaml
server:
  port: 8080
  shutdown: graceful

management:
  server:
    port: 9000  # Internal-only

eureka:
  client:
    enabled: false  # Use cloud registry
```

---

## Docker Configuration

### Standard Dockerfile Template

All services use this pattern:

```dockerfile
FROM eclipse-temurin:25-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/{service-name}-*.jar {service-name}.jar
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar {service-name}.jar"]
```

### Docker Compose Service Pattern

**Gateway (fixed port):**

```yaml
pos-api-gateway:
  build:
    context: ./pos-api-gateway
  ports:
    - "8080:8080"
  environment:
    EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://eureka-server:8761/eureka/
  depends_on:
    eureka-server:
      condition: service_healthy
```

**Dynamic port service:**

```yaml
pos-catalog:
  build:
    context: ./pos-catalog
  # No ports exposed; uses dynamic port
  environment:
    EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://eureka-server:8761/eureka/
  depends_on:
    eureka-server:
      condition: service_healthy
```

### Key Features

- **Lightweight Base Image**: `eclipse-temurin:25-jdk-alpine`
- **JAVA_OPTS Support**: Runtime JVM tuning
- **Health Checks**: Configured per service
- **Service Discovery**: Eureka for all internal communication

---

## Inter-Service Communication

### Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                    External Clients / Frontend                │
└──────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────┐
│                    API Gateway (port 8080)                    │
│  • ApiVersionHeaderToPathFilter                              │
│  • Explicit route whitelist (discovery locator disabled)      │
└──────────────────────────────────────────────────────────────┘
                                │
         ┌──────────────────────┼──────────────────────┐
         ▼                      ▼                      ▼
   ┌───────────┐          ┌───────────┐          ┌───────────┐
   │ inventory │◄────────►│ customer  │◄────────►│ security  │
   │ (Eureka)  │          │ (Eureka)  │          │ (Eureka)  │
   └───────────┘          └───────────┘          └───────────┘
```

### Security Ownership Model

- `pos-security-service` is the source of truth for identities, roles, permissions, and assignments.
- API Gateway is the authentication enforcement boundary for external traffic.
- Backend services perform authorization with `@PreAuthorize` using gateway-established authority context.
- See [ADR-0011](../../durion/docs/adr/0011-api-gateway-security-architecture.adr.md) for the canonical trust model.

### Strategy 1: Through API Gateway

Use the gateway for client-facing traffic and for calls that must pass through gateway-level controls:

```yaml
gateway:
  url: ${GATEWAY_URL:http://localhost:8080}
```

```java
@Configuration
public class ServiceClientConfig {
    @Value("${gateway.url:http://localhost:8080}")
    private String gatewayUrl;

    @Bean
    public RestClient gatewayRestClient() {
        return RestClient.builder()
            .baseUrl(gatewayUrl)
            .defaultHeader("X-API-Version", "1")
            .build();
    }
}
```

### Strategy 2: Direct Eureka Load-Balanced Calls

For performance-critical internal service-to-service calls:

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
        return builder.baseUrl("http://security-service").build();
    }
}
```

### Service Name Registry

| Module | Eureka Service Name | Gateway Path |
| -------- | --------------------- | -------------- |
| pos-accounting | `accounting` | `/accounting/**` |
| pos-catalog | `catalog` | `/catalog/**` |
| pos-customer | `customer` | `/customer/**` |
| pos-inventory | `inventory` | `/inventory/**` |
| pos-order | `order` | `/order/**` |
| pos-people | `people` | `/people/**` |
| pos-workorder | `workorder` | `/workorder/**` |
| pos-security-service | `security-service` | `/security-service/**` |
| pos-shop-manager | `shop-manager` | `/shop-manager/**` |

---

## Observability

### Stack Overview

| Component | Image | Port | Purpose |
| ----------- | ------- | ------ | --------- |
| Jaeger | jaegertracing/all-in-one:1.54 | 16686 | Distributed tracing UI |
| Prometheus | prom/prometheus:v2.49.1 | 9090 | Metrics collection |
| Grafana | grafana/grafana:10.3.3 | 3000 | Visualization |
| OTEL Collector | otel/opentelemetry-collector | 4317/4318 | Telemetry pipeline |

### Quick Start

```bash
# Start observability stack
docker-compose up -d jaeger prometheus grafana otel-collector

# Access dashboards
# Grafana:     http://localhost:3000  (admin/admin)
# Jaeger UI:   http://localhost:16686
# Prometheus:  http://localhost:9090
```

### OpenTelemetry Configuration

All Dockerfiles include Grafana OpenTelemetry Java Agent v2.9.0:

```dockerfile
RUN curl -L -o /opt/grafana-opentelemetry-java.jar \
    https://github.com/grafana/grafana-opentelemetry-java/releases/download/v2.9.0/grafana-opentelemetry-java.jar

ENV OTEL_SERVICE_NAME=pos-{service}
ENV OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
ENV OTEL_EXPORTER_OTLP_PROTOCOL=grpc

ENTRYPOINT ["sh", "-c", "exec java -javaagent:/opt/grafana-opentelemetry-java.jar $JAVA_OPTS -jar {service}.jar"]
```

### Shared Configuration (`application-observability.yml`)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  tracing:
    sampling:
      probability: 1.0  # 100% in dev, 10% in prod

otel:
  exporter:
    otlp:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4317}
```

---

## Correlation ID Implementation

### Standard: `X-Correlation-Id` Header

All requests should include correlation IDs for distributed tracing.

### Backend Pattern

```java
@PostMapping("/resource")
public ResponseEntity<?> createResource(
    @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
    @RequestBody ResourceRequest request) {

    log.info("Create resource requested. correlationId={}", correlationId);
    return ResponseEntity.ok(resourceService.create(request, correlationId));
}
```

### Error Response Pattern

```java
public class ErrorResponse {
    private String errorCode;
    private String message;
    private String correlationId;
    private Instant timestamp;

    public ErrorResponse(String errorCode, String message, String correlationId) {
        this.errorCode = errorCode;
        this.message = message;
        this.correlationId = correlationId;
        this.timestamp = Instant.now();
    }
}
```

### Frontend Pattern

```typescript
import { fetchWithCorrelation, parseErrorResponse } from '@/utils/correlationId';

const response = await fetchWithCorrelation('/api/v1/resource', {
  method: 'POST',
  body: JSON.stringify(data)
});

if (!response.ok) {
  const error = await parseErrorResponse(response);
  console.error(`Error: ${error.message} (Correlation ID: ${error.correlationId})`);
}
```

---

## PostgreSQL Setup

### Connection Details

| Property | Value |
| ---------- | ------- |
| Host | `postgres` (Docker) / `localhost` (host) |
| Port | 5432 |
| Username | `${POSTGRES_USER}` |
| Password | `${POSTGRES_PASSWORD}` |
| Database | `${POSTGRES_DB}` |

### JDBC Connection String

```
jdbc:postgresql://postgres:5432/${POSTGRES_DB}
```

### Docker Compose Configuration

```yaml
postgres:
  image: postgres:16-alpine
  container_name: postgres-positivity
  environment:
    POSTGRES_USER: ${POSTGRES_USER}
    POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    POSTGRES_DB: ${POSTGRES_DB}
  ports:
    - "5432:5432"
  volumes:
    - postgres-data:/var/lib/postgresql/data
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER}"]
    interval: 10s
    timeout: 5s
    retries: 5
```

### Useful Commands

```bash
# Connect to PostgreSQL
docker-compose exec postgres psql -U positivity -d positivity

# Backup database
docker-compose exec postgres pg_dump -U positivity positivity > backup.sql

# Check readiness
docker-compose exec postgres pg_isready -U positivity
```

---

## References

- [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway)
- [Netflix Eureka](https://github.com/Netflix/eureka)
- [OpenTelemetry Java](https://opentelemetry.io/docs/languages/java/)
- [Grafana OTEL Java Agent](https://github.com/grafana/grafana-opentelemetry-java)
