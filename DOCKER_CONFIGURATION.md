# Docker Configuration Summary – durion-positivity-backend

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
EXPOSE {port}
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar {service-name}.jar"]
```

## Service Port Mapping

### API Gateway & External Entry Points

| Service | Port | Route | Purpose |
|---------|------|-------|---------|
| **pos-api-gateway** | 8080 | External entry point | Routes all external requests to backend services |
| **pos-service-discovery** (Eureka) | 8761 | Service discovery | Eureka server for service registration |

### Public-Facing Backend Services (Accessed via Gateway)

| Service | Internal Port | Gateway Route | Dockerfile |
|---------|---------------|---------------|-----------|
| pos-catalog | 8081 | `/catalog/**` | `pos-catalog/Dockerfile` |
| pos-customer | 8082 | `/customer/**` | `pos-customer/Dockerfile` |
| pos-image | 8083 | `/image/**` | `pos-image/Dockerfile` |
| pos-location | 8084 | `/location/**` | `pos-location/Dockerfile` |
| pos-people | 8085 | `/people/**` | `pos-people/Dockerfile` |
| pos-security-service | 8086 | `/security-service/**` | `pos-security-service/Dockerfile` |
| pos-shop-manager | 8087 | `/shop-manager/**` | `pos-shop-manager/Dockerfile` |
| pos-vehicle-fitment | 8088 | `/vehicle-fitment/**` | `pos-vehicle-fitment/Dockerfile` |
| pos-vehicle-inventory | 8089 | `/vehicle-inventory/**` | `pos-vehicle-inventory/Dockerfile` |
| pos-work-order | 8090 | `/workorder/**` | `pos-work-order/Dockerfile` |
| pos-vehicle-reference-carapi | 8091 | `/vehicle-reference-carapi/**` | `pos-vehicle-reference-carapi/Dockerfile` |
| pos-vehicle-reference-nhtsa | 8092 | `/vehicle-reference-nhtsa/**` | `pos-vehicle-reference-nhtsa/Dockerfile` |

### Internal Services (Not Exposed via Gateway)

| Service | Internal Port | Purpose | Dockerfile |
|---------|---------------|---------|-----------|
| pos-accounting | 9001 | Accounting operations | `pos-accounting/Dockerfile` |
| pos-event-receiver | 9002 | Event consumption | `pos-event-receiver/Dockerfile` |
| pos-events | 9003 | Event publishing | `pos-events/Dockerfile` |
| pos-inquiry | 9004 | Inquiry services | `pos-inquiry/Dockerfile` |
| pos-inventory | 9005 | Inventory management | `pos-inventory/Dockerfile` |
| pos-invoice | 9006 | Invoice operations | `pos-invoice/Dockerfile` |
| pos-order | 9007 | Order services | `pos-order/Dockerfile` |
| pos-price | 9008 | Pricing services | `pos-price/Dockerfile` |
| pos-agent-framework | 8080 | Event-driven agents | `pos-agent-framework/Dockerfile` (Multi-stage) |

## Key Features of Updated Dockerfiles

✅ **Lightweight Base Image**: `openjdk:21-jdk-alpine` reduces image size compared to standard JDK images  
✅ **Service-Specific JARs**: Each Dockerfile copies the correct module JAR  
✅ **Correct Port Exposure**: Each service exposes its internal port for docker-compose networking  
✅ **JAVA_OPTS Support**: Environment variable passed for runtime JVM tuning  
✅ **Standardized Format**: Consistency across all services for maintainability  

## Docker Compose Integration

The `docker-compose.yml` orchestrates all services with:

- **Service discovery**: Eureka server (port 8761) handles service registration
- **API Gateway routing**: Single entry point at port 8080 routes to backend services
- **Internal service networking**: Services communicate via Docker DNS using service names
- **Health checks**: Each service has configured health checks for orchestration
- **Environment variables**: EUREKA configuration injected for service discovery

### Example Docker Compose Service

```yaml
pos-catalog:
  build:
    context: ./pos-catalog
    dockerfile: Dockerfile
  ports:
    - "8081:8081"  # Port mapping: host:container
  environment:
    EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://eureka-server:8761/eureka/
  depends_on:
    eureka-server:
      condition: service_healthy
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]
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
- **Direct Service** (for testing): `http://localhost:8081/catalog/...`
- **Eureka Dashboard**: `http://localhost:8761/`

## Architecture Flow

```
External Client
       ↓
http://localhost:8080/catalog/...
       ↓
[API Gateway - pos-api-gateway:8080]
       ↓
[Service Discovery - Eureka:8761] → resolves CATALOG_SERVICE
       ↓
[Backend Service - pos-catalog:8081]
       ↓
[Internal Services via Docker DNS]
```

## Notes

- **Naming Convention**: workorder is consistently used as one word across all services
- **Port Ranges**: 
  - `8080-8092`: Public-facing services
  - `8761`: Eureka/Service Discovery
  - `9001-9008`: Internal services
- **No External Repository**: All Dockerfiles use public `openjdk` image from Docker Hub
- **Zero-Downtime Considerations**: Services register/deregister with Eureka for graceful orchestration
