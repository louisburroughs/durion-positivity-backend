# Port Strategy Guide — durion-positivity-backend

## Goals

1. **No accidental conflicts on dev machines/CI** — Services use dynamic ports; no collisions on parallel runs
2. **Stable, predictable ingress in higher environments** — Gateway on port 8080; service discovery handles routing

## Baseline Architecture

### Fixed Ports (Well-Known)

| **Component** | **Port** | **Environment** | **Notes** |
|---|---|---|---|
| **API Gateway** | 8080 | All (dev/prod) | Single external entry point; all upstream requests routed here |
| **Eureka Server** | 8761 | Dev/local only | Service registry; disabled in prod (use cloud registry) |
| **Management (Actuator)** | Internal-only | Prod | Health, metrics, prometheus; not exposed via public listener |

### Dynamic Ports (Ephemeral)

All downstream services use **`server.port: 0`** to get OS-assigned ephemeral ports:

- pos-catalog, pos-customer, pos-image, pos-location, pos-people, pos-security-service
- pos-shop-manager, pos-vehicle-*, pos-workorder, pos-inventory, pos-order, pos-price, pos-accounting
- pos-event-receiver, pos-inquiry, pos-invoice

Services **register with Eureka** using the actual assigned port; gateway discovers them dynamically via `lb://SERVICE_ID`.

### Management Port Isolation

- **Development**: `management.server.port: 0` (ephemeral, internal-only)
- **Production**: `management.server.port: 9000` (internal network only, not exposed)

This isolates ops traffic (health, metrics) from business API traffic.

## Implementation by Profile

### Local Development (`application-local.yml`)

```yaml
server:
  port: 0  # OS assigns available port

management:
  server:
    port: 0  # OS assigns available port, internal-only
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

# Logging: capture assigned port for debugging
logging:
  level:
    org.springframework.boot.web.embedded.tomcat.TomcatWebServer: INFO
```

When running locally:
- Application logs show `Tomcat started on port(s): XXXXX`
- Eureka dashboard at `http://localhost:8761/eureka/` shows all instances with actual ports
- Gateway at `http://localhost:8080` routes to services via `lb://SERVICE_ID`

### Testing / CI (`application-test.yml` or Maven profile)

```yaml
server:
  port: 0

eureka:
  client:
    enabled: false  # Disable Eureka for isolated tests

spring:
  cloud:
    discovery:
      enabled: false
```

Use `@TestPropertySource` or Maven `--server.port=0 --eureka.client.enabled=false` to override.

### Production (`application-prod.yml` or K8s ConfigMap)

```yaml
server:
  port: 8080  # Fixed internally; exposed via Ingress/LB
  shutdown: graceful

management:
  server:
    port: 9000  # Internal-only; not exposed publicly

eureka:
  client:
    enabled: false  # Use cloud registry (Consul, Kubernetes DNS, etc.)
  # OR: use cloud provider's service discovery

spring:
  cloud:
    # Use cloud provider's discovery client (Kubernetes, AWS, etc.)
    discovery:
      client:
        simple:
          # Define downstream services via ConfigMap/Secrets
```

## Docker Compose Strategy

### For Local Dev (`docker-compose.yml`)

```yaml
version: '3.8'

networks:
  pos-network:
    driver: bridge

services:
  eureka-server:
    image: pos-service-discovery:latest
    container_name: eureka-server
    ports:
      - "8761:8761"  # Fixed: humans need this
    environment:
      - SPRING_PROFILES_ACTIVE=local

  pos-api-gateway:
    image: pos-api-gateway:latest
    container_name: pos-api-gateway
    ports:
      - "8080:8080"  # Fixed: external entry point
    environment:
      - SPRING_PROFILES_ACTIVE=local
      - EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://eureka-server:8761/eureka/
    depends_on:
      - eureka-server
    networks:
      - pos-network

  pos-catalog:
    image: pos-catalog:latest
    container_name: pos-catalog
    # NO ports exposed (services communicate via Docker DNS)
    environment:
      - SPRING_PROFILES_ACTIVE=local
      - SERVER_PORT=0
      - EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://eureka-server:8761/eureka/
    depends_on:
      - eureka-server
    networks:
      - pos-network
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:${LOCAL_SERVER_PORT}/actuator/health"]
      # Note: LOCAL_SERVER_PORT is exposed by Spring; adjust as needed

  # ... repeat pattern for all downstream services
```

**Key principles:**
- Only gateway (8080) and Eureka (8761) get host port mappings
- All other services use Docker DNS; no host ports exposed
- Services communicate via `http://service-name:8080` (container port, not host)
- `SPRING_PROFILES_ACTIVE=local` activates `application-local.yml` in each container

### For CI/Integration Tests

```yaml
# Minimal compose: only gateway + Eureka, disable discovery for tests
services:
  eureka-server:
    ports:
      - "8761:8761"

  pos-api-gateway:
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=test

  # Individual services started via Maven --server.port=0, Eureka disabled
```

## Migration Path

### Step 1: Verify Eureka is Running
```bash
curl http://localhost:8761/eureka/apps
# Should list all registered services with actual (dynamic) ports
```

### Step 2: Run Gateway + All Services Locally
```bash
# From workspace root
docker-compose -f durion-positivity-backend/docker-compose.yml up

# Or use Compose profiles:
docker-compose -f durion-positivity-backend/docker-compose.yml --profile observability up
```

### Step 3: Test Gateway Routing
```bash
# Gateway discovers services via Eureka
curl http://localhost:8080/catalog/products
# → routes to lb://CATALOG_SERVICE → dynamically resolved port

# Direct service (useful for debugging):
curl http://localhost:8761/eureka/apps
# → find pos-catalog instance port, e.g., 32948
curl http://localhost:32948/catalog/products
```

### Step 4: Parallel Local Services
```bash
# Run two instances of same service on different ports (local only)
SERVICE_PORT=0 java -jar pos-catalog.jar

# Both register with Eureka; gateway load-balances between them
```

## Troubleshooting

### Service not appearing in Eureka
- Check `eureka.client.register-with-eureka=true` in `application-local.yml`
- Verify Eureka server is reachable: `curl http://localhost:8761/eureka/eureka/status`
- Check service logs: `Registering new instance ...`

### Gateway returns 503 Service Unavailable
- Eureka may not have instances yet; wait 30-60 seconds for registration + heartbeat
- Verify service registered: `curl http://localhost:8761/eureka/apps/SERVICE_ID`
- Check gateway logs for route configuration

### Port already in use (8080 or 8761)
- Kill process: `lsof -ti:8080 | xargs kill -9`
- Or use different compose file / override env vars

### Management endpoints returning 404
- Verify `management.endpoints.web.exposure.include` includes `health,info,metrics,prometheus`
- Hit correct port: `http://localhost:9000/actuator/health` (if separate management port)

## See Also

- [DOCKER_CONFIGURATION.md](DOCKER_CONFIGURATION.md) — Docker-specific setup
- [AGENTS.md](AGENTS.md) — Developer workflow and build commands
- Parent policy: `../../durion/docs/governance/PORT_STRATEGY.md` (if exists)
