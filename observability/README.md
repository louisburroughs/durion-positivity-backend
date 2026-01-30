# Durion POS Backend - Observability Stack

## Overview

This directory contains the observability infrastructure for the Durion POS Backend system. The stack includes:

- **Jaeger**: Distributed tracing system for monitoring and troubleshooting microservices
- **Prometheus**: Time-series database for metrics collection
- **Grafana**: Visualization and analytics platform
- **OpenTelemetry Collector**: Unified telemetry data collection and processing

## Architecture

```
┌─────────────────┐
│   POS Services  │
│  (Spring Boot)  │
└────────┬────────┘
         │ OTLP
         ↓
┌─────────────────┐     ┌──────────┐
│ OTEL Collector  │────→│  Jaeger  │ (Traces)
└────────┬────────┘     └──────────┘
         │
         ├──────────────→┌────────────┐
         │               │ Prometheus │ (Metrics)
         │               └──────┬─────┘
         │                      │
         │               ┌──────▼─────┐
         └──────────────→│  Grafana   │ (Visualization)
                         └────────────┘
```

## Quick Start

### 1. Start the Observability Stack

From the repository root:

```bash
# Start all services including observability
docker-compose up -d

# Or start only observability services
docker-compose up -d jaeger prometheus grafana otel-collector
```

### 2. Access the Dashboards

- **Grafana**: http://localhost:3000
  - Username: `admin`
  - Password: `admin`
  
- **Jaeger UI**: http://localhost:16686
  - View distributed traces across services
  
- **Prometheus**: http://localhost:9090
  - Query metrics and explore targets

- **OTEL Collector Health**: http://localhost:13133
  - Check collector status

### 3. Verify Services are Reporting

1. **Check Prometheus Targets**:
   - Navigate to http://localhost:9090/targets
   - All POS services should show as "UP"

2. **Check Jaeger for Traces**:
   - Navigate to http://localhost:16686
   - Select a service from the dropdown
   - Click "Find Traces"

3. **Check Grafana**:
   - Navigate to http://localhost:3000
   - Go to Explore → Prometheus
   - Query: `up{job=~"pos-.*"}`

## Configuration Files

### `prometheus.yml`
Defines scrape jobs for all POS services. Each service exposes metrics at `/actuator/prometheus`.

**Default Scrape Interval**: 15 seconds

**Exposed Ports**:
- Service health metrics on configured service ports
- Actuator endpoints at `<service>:<port>/actuator/prometheus`

### `otel-collector-config.yml`
Configures the OpenTelemetry Collector pipeline:

**Receivers**:
- OTLP gRPC: Port 4317
- OTLP HTTP: Port 4318
- Prometheus: Self-scraping

**Processors**:
- Batch: Groups telemetry for efficiency
- Memory Limiter: Prevents OOM (512 MiB limit)
- Resource: Adds environment attributes
- Attributes: Custom attribute manipulation

**Exporters**:
- Jaeger: Traces via OTLP
- Prometheus: Metrics on port 8889
- Logging: Debug output

### `application-observability.yml`
Shared Spring Boot configuration for OpenTelemetry integration. Services can import this configuration:

```yaml
spring:
  config:
    import: optional:file:../application-observability.yml
```

**Key Features**:
- OTLP exporter configuration
- Trace sampling (100% in dev, 10% in prod)
- Metrics export intervals
- Actuator endpoint exposure
- Log correlation with trace/span IDs

## Service Configuration

### Environment Variables

Each service supports the following observability environment variables:

```bash
# OpenTelemetry Configuration
OTEL_SERVICE_NAME=pos-<service-name>
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_RESOURCE_ATTRIBUTES="service.name=DURION-POSITIVITY-BACKEND,service.namespace=durion-group"

# Trace Sampling
OTEL_TRACES_SAMPLER_PROBABILITY=1.0  # 100% sampling

# Environment
DEPLOYMENT_ENVIRONMENT=development
```

### Dockerfile Integration

All service Dockerfiles include the Grafana OpenTelemetry Java Agent:

```dockerfile
# Download agent
RUN curl -L -o /opt/grafana-opentelemetry-java.jar \
    https://github.com/grafana/grafana-opentelemetry-java/releases/download/v2.9.0/grafana-opentelemetry-java.jar

# Run with agent
ENTRYPOINT ["sh", "-c", "exec java -javaagent:/opt/grafana-opentelemetry-java.jar $JAVA_OPTS -jar <service>.jar"]
```

## Metrics Available

### Spring Boot Actuator Metrics

- `http.server.requests` - HTTP request metrics
- `jvm.memory.used` - JVM memory usage
- `jvm.gc.pause` - Garbage collection metrics
- `system.cpu.usage` - CPU utilization
- `process.uptime` - Service uptime
- `spring.cloud.gateway.requests` - Gateway-specific metrics (API Gateway only)

### Custom Business Metrics

Services can expose custom metrics using Micrometer:

```java
@Service
public class OrderService {
    private final Counter orderCounter;
    
    public OrderService(MeterRegistry registry) {
        this.orderCounter = Counter.builder("pos.orders.created")
            .description("Total orders created")
            .tag("service", "pos-order")
            .register(registry);
    }
    
    public void createOrder() {
        // Business logic
        orderCounter.increment();
    }
}
```

## Traces

### Automatic Instrumentation

The OpenTelemetry Java Agent automatically instruments:

- HTTP requests (incoming and outgoing)
- Database queries (JDBC, JPA, Hibernate)
- Spring components (@Service, @Controller, @Repository)
- RestTemplate and WebClient calls
- Kafka producers and consumers

### Custom Spans

Add custom spans for business operations:

```java
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

@Service
public class PaymentService {
    private final Tracer tracer;
    
    public void processPayment(Order order) {
        Span span = tracer.spanBuilder("processPayment")
            .setAttribute("order.id", order.getId())
            .setAttribute("amount", order.getTotal())
            .startSpan();
        
        try {
            // Payment processing logic
        } finally {
            span.end();
        }
    }
}
```

## Dashboards

### Pre-configured Datasources

Grafana is automatically configured with:

1. **Prometheus** (default) - Metrics
2. **Jaeger** - Distributed traces
3. **Tempo** - Alternative trace backend (optional)

### Importing Dashboards

1. Navigate to Grafana → Dashboards → Import
2. Use Dashboard ID or paste JSON
3. Select Prometheus as the datasource

**Recommended Dashboards**:
- **Spring Boot 2.1 System Monitor** (ID: 11378)
- **JVM (Micrometer)** (ID: 4701)
- **Spring Cloud Gateway** (ID: 11506)
- **OpenTelemetry Collector** (ID: 15983)

## Troubleshooting

### Services Not Reporting Metrics

1. **Check service health**:
   ```bash
   curl http://localhost:<service-port>/actuator/health
   ```

2. **Verify Prometheus endpoint**:
   ```bash
   curl http://localhost:<service-port>/actuator/prometheus
   ```

3. **Check OTEL Collector logs**:
   ```bash
   docker-compose logs otel-collector
   ```

### No Traces in Jaeger

1. **Verify OTLP endpoint is reachable**:
   ```bash
   docker-compose exec pos-<service> curl -v http://otel-collector:4318/v1/traces
   ```

2. **Check agent is loaded**:
   ```bash
   docker-compose logs pos-<service> | grep -i "opentelemetry"
   ```

3. **Increase log verbosity**:
   ```bash
   OTEL_LOG_LEVEL=debug docker-compose up pos-<service>
   ```

### High Memory Usage

1. **Check OTEL Collector memory**:
   ```bash
   docker stats otel-collector
   ```

2. **Adjust memory limiter** in `otel-collector-config.yml`:
   ```yaml
   processors:
     memory_limiter:
       limit_mib: 256  # Reduce from 512
   ```

3. **Reduce trace sampling** in production:
   ```yaml
   otel:
     traces:
       sampler:
         probability: 0.1  # Sample 10%
   ```

## Production Considerations

### Grafana Cloud Integration

To send telemetry to Grafana Cloud:

1. **Update docker-compose.yml**:
   ```yaml
   environment:
     OTEL_EXPORTER_OTLP_ENDPOINT: https://otlp-gateway-prod-us-east-3.grafana.net/otlp
     OTEL_EXPORTER_OTLP_HEADERS: "Authorization=Basic <base64-encoded-credentials>"
   ```

2. **Reduce local stack**:
   - Disable local Jaeger and Prometheus
   - Keep Grafana for local dashboards (optional)

### Security

1. **Enable authentication** on Prometheus:
   - Add basic auth
   - Use TLS certificates

2. **Restrict Grafana access**:
   - Change default admin password
   - Configure LDAP/OAuth

3. **Network isolation**:
   - Use internal networks
   - Expose only necessary ports

### Scaling

1. **Horizontal Collector Scaling**:
   ```yaml
   otel-collector:
     deploy:
       replicas: 3
   ```

2. **Add Persistent Storage**:
   - Use external Prometheus storage (Thanos, Cortex)
   - Configure remote write

3. **Enable High Availability**:
   - Use Prometheus federation
   - Deploy multiple Jaeger collectors

## Resources

- [OpenTelemetry Java Instrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation)
- [Grafana OpenTelemetry Java](https://github.com/grafana/grafana-opentelemetry-java)
- [Prometheus Documentation](https://prometheus.io/docs/)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/grafana/latest/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Micrometer Metrics](https://micrometer.io/docs)

## Support

For issues or questions:
1. Check service logs: `docker-compose logs <service-name>`
2. Verify configuration files
3. Consult the troubleshooting section above
4. Review OpenTelemetry and Spring Boot documentation

---

**Last Updated**: January 2026  
**Version**: 1.0.0  
**Maintainer**: Durion Platform Team
