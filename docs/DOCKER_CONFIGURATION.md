# Docker Configuration Summary – durion-positivity-backend

## Port Strategy Overview

**As of January 2026**, the backend implements a **dynamic port strategy**:

- **Gateway (pos-api-gateway)**: Fixed port **8080** (only service with fixed port; single external entry point)
- **Eureka (pos-service-discovery)**: Fixed port **8761** (service discovery; internal use)
- **All other services**: **Dynamic ports** (`server.port: 0`) — OS assigns ephemeral port, service registers with Eureka
- **Management ports**: Internal-only (dynamic), not exposed externally

**Benefits:**
- No port conflicts on dev machines or CI/CD parallel runs
- Services auto-discover each other via Eureka
- Scalable: multiple instances of same service can run simultaneously

For detailed strategy, see [PORT_STRATEGY.md](PORT_STRATEGY.md).

## Updated Dockerfiles

All Dockerfiles have been updated to use `openjdk:21-jdk-alpine` as the base image instead of company-specific images. This enables the project to be built and deployed independently without external repository dependencies.

### Standard Dockerfile Template

All services now follow this standardized pattern:

```dockerfile
FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/{service-name}-*.jar {service-name}.jar
# Services use dynamic ports (server.port: 0) via Spring config
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar {service-name}.jar"]
```

## Service Port Mapping

### Fixed Ports (Gateway Only)

| Service | Port | Purpose |
|---------|------|---------|
| **pos-api-gateway** | 8080 | Single external entry point; routes all external requests to backend services via service discovery |
| **pos-service-discovery** (Eureka) | 8761 | Service registry; backend services auto-register and discover each other |

### Dynamic Ports (All Other Services)

All pos-* services (except gateway) use **`server.port: 0`** for OS-assigned ephemeral ports:

- pos-catalog, pos-customer, pos-image, pos-location, pos-people, pos-security-service
- pos-shop-manager, pos-vehicle-fitment, pos-vehicle-inventory, pos-workorder, pos-vehicle-reference-carapi, pos-vehicle-reference-nhtsa
- pos-accounting, pos-event-receiver, pos-events, pos-inquiry, pos-inventory, pos-invoice, pos-order, pos-price
- pos-agent-framework, pos-archunit, and others

**How it works:**
1. Service starts with `server.port: 0`
2. OS assigns available ephemeral port
3. Service registers with Eureka using actual assigned port
4. Gateway discovers service via Eureka and routes requests using `lb://SERVICE_NAME` (Spring Cloud load balancing)

## Key Features of Updated Dockerfiles

✅ **Lightweight Base Image**: `openjdk:21-jdk-alpine` reduces image size compared to standard JDK images  
✅ **Service-Specific JARs**: Each Dockerfile copies the correct module JAR  
✅ **Correct Port Exposure**: Each service exposes its internal port for docker-compose networking  
✅ **JAVA_OPTS Support**: Environment variable passed for runtime JVM tuning  
✅ **Standardized Format**: Consistency across all services for maintainability  

## Docker Compose Integration

The `compose.yaml` orchestrates all services with:

- **Service discovery**: Eureka server (port 8761) handles service registration
- **API Gateway**: Single entry point at port 8080 routes to backend services (discovered via Eureka)
- **Dynamic port assignment**: Services run on OS-assigned ephemeral ports, no collisions
- **Internal service networking**: Services communicate via Docker DNS and Eureka load balancing
- **Health checks**: Each service has configured health checks for orchestration
- **Environment variables**: EUREKA configuration injected for service discovery

### Example Docker Compose Service (Dynamic Port)

```yaml
pos-catalog:
  build:
    context: ./pos-catalog
    dockerfile: Dockerfile
  # No ports exposed; dynamic port via server.port: 0
  environment:
    EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://eureka-server:8761/eureka/
  depends_on:
    eureka-server:
      condition: service_healthy
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
    # or use management port if exposed internally
```

### Example Docker Compose Service (Gateway Only Fixed Port)

```yaml
pos-api-gateway:
  build:
    context: ./pos-api-gateway
    dockerfile: Dockerfile
  ports:
    - "8080:8080"  # Only gateway uses fixed port
  environment:
    EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://eureka-server:8761/eureka/
  depends_on:
    eureka-server:
      condition: service_healthy
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
```

## Building and Running

### Build Individual Service

```bash
cd durion-positivity-backend/pos-catalog
./mvnw clean package
docker build -t pos-catalog:latest .
```

### Run with Docker Compose

```bash
cd durion-positivity-backend
docker-compose up -d
```

### Access Endpoints

- **External (via Gateway)**: `http://localhost:8080/catalog/...`
- **Eureka Dashboard**: `http://localhost:8761/`
- **Service internal discovery**: Services find each other via Eureka (no direct port access needed)

## Local Development (Non-Docker)

```bash
# Terminal 1: Start Eureka
cd pos-service-discovery
./mvnw spring-boot:run
# Output: Eureka starts on dynamic port, logs show actual port

# Terminal 2: Start Gateway
cd pos-api-gateway
./mvnw spring-boot:run
# Output: Gateway on port 8080

# Terminal 3+: Start services
cd pos-catalog
./mvnw spring-boot:run
# Output: Service on dynamic port, registers with Eureka
```

Services auto-discover each other via Eureka at `http://localhost:8761/eureka/`.

## Architecture Flow (New Dynamic Model)

```
External Client
       ↓
http://localhost:8080/catalog/...
       ↓
[API Gateway - pos-api-gateway:8080]
       ↓
[Service Discovery - Eureka:8761] → resolves CATALOG_SERVICE via load balancing
       ↓
[Backend Service - pos-catalog:{DYNAMIC_PORT}]
       ↓
[Other Internal Services via Eureka]
```

## Notes

- **Naming Convention**: workorder is consistently used as one word across all services
- **Port Strategy**: 
  - Gateway only on fixed port 8080
  - All services use dynamic ports (server.port: 0)
  - Service discovery via Eureka ensures no conflicts
- **No External Repository**: All Dockerfiles use public `openjdk` image from Docker Hub
- **Zero-Downtime Considerations**: Services register/deregister with Eureka for graceful orchestration
- **Parallel Execution**: Multiple service instances can run simultaneously without port conflicts
