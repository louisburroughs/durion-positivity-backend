# pos-service-discovery

Netflix Eureka server providing service discovery for all Durion POS microservices. Every `pos-*` service registers with this server on startup and uses it to resolve service addresses for load-balanced inter-service calls.

## Responsibilities

- Run the Eureka server registry that all microservices register with at startup
- Provide a dashboard at the application root showing registered service instances
- Serve as the single source of truth for service instance availability

## Key Classes

- `PosServiceDiscoveryApplication` — Spring Boot main class with `@EnableEurekaServer`
- `EurekaWebConfig` — configures web layer for the Eureka dashboard

## Configuration

| Property                             | Default | Description                            |
| ------------------------------------ | ------- | -------------------------------------- |
| `server.port`                        | `8761`  | Eureka server port                     |
| `eureka.client.register-with-eureka` | `false` | Server does not register with itself   |
| `eureka.client.fetch-registry`       | `false` | Server does not fetch its own registry |

## Dependencies

No internal `pos-*` module dependencies. Depends only on Spring Cloud Netflix Eureka Server.

## Development

```bash
./mvnw -pl pos-service-discovery -am spring-boot:run
```

Eureka dashboard: `http://localhost:8761`

This service must be started first before any other `pos-*` service.
