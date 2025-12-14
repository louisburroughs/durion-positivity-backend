# Technology Stack

## Core Framework

- **Spring Boot 3.2.6** - Primary application framework for microservices
- **Spring Cloud 2023.0.1** - Microservices infrastructure and patterns
- **Java 21** - Modern Java runtime with latest features
- **Maven** - Build system and dependency management
- **Lombok** - Code generation for reducing boilerplate

## Microservices Infrastructure

- **Spring Cloud Gateway** - API Gateway for routing and load balancing
- **Spring Cloud Netflix Eureka** - Service discovery and registration
- **Spring Security** - Authentication and authorization
- **Spring Boot Actuator** - Health checks and metrics
- **Spring Data JPA** - Data access layer abstraction

## Database Support

- **PostgreSQL** - Recommended production database for each service
- **MySQL** - Alternative production database option
- **H2** - Embedded database for development and testing
- **Spring Data JPA** - ORM and repository pattern implementation

## Messaging & Events

- **Apache Kafka** - Event streaming and asynchronous communication
- **RabbitMQ** - Alternative message broker for event processing
- **Spring Cloud Stream** - Message-driven microservices framework

## Containerization & Deployment

- **Docker** - Container runtime for service packaging
- **Docker Compose** - Multi-container orchestration for development
- **Kubernetes** - Production container orchestration (optional)
- **Spring Boot Docker Support** - Native container image building

## Build System

### Common Commands

```bash
# Development Build (fast iteration)
mvn clean compile -DskipTests

# Full Build with Tests
mvn clean install

# Build Specific Service
mvn clean install -pl pos-catalog

# Run All Tests
mvn test

# Run Specific Service Tests
mvn test -pl pos-inventory

# Package for Docker
mvn clean package spring-boot:build-image
```

### Docker Commands

```bash
# Start Development Environment
docker-compose up -d

# View Service Logs
docker-compose logs -f pos-api-gateway

# Stop All Services
docker-compose down

# Clean Restart (removes data)
docker-compose down -v && docker-compose up -d

# Build and Start
docker-compose up --build
```

### Service Management

```bash
# Start Service Discovery
java -jar pos-service-discovery/target/pos-service-discovery-*.jar

# Start API Gateway
java -jar pos-api-gateway/target/pos-api-gateway-*.jar

# Start Individual Service
java -jar pos-catalog/target/pos-catalog-*.jar
```

## Configuration Files

- **pom.xml** - Root Maven configuration and module definitions
- **application.yml** - Service-specific configuration
- **bootstrap.yml** - Service discovery and config server settings
- **docker-compose.yml** - Multi-service development environment
- **Dockerfile** - Container build instructions per service

## Development Tools

- **IntelliJ IDEA** - Recommended IDE with Spring Boot support
- **VSCode** - Secondary IDE
- **Spring Boot DevTools** - Hot reloading and development utilities
- **Maven Wrapper** - Ensures consistent build environment
- **Spring Boot Actuator** - Development monitoring and health checks
- **Postman/Insomnia** - API testing and development

## Observability & Monitoring

- **Micrometer** - Metrics collection and export
- **Prometheus** - Metrics storage and alerting
- **Grafana** - Metrics visualization and dashboards
- **Zipkin/Jaeger** - Distributed tracing
- **ELK Stack** - Centralized logging (Elasticsearch, Logstash, Kibana)

## Security Stack

- **Spring Security** - Core security framework
- **JWT** - Token-based authentication and authorization
- **Spring Cloud Security** - Microservices security patterns
- **HTTPS/TLS** - Secure communication between services

## Testing Framework

- **JUnit 5** - Unit testing framework
- **Mockito** - Mocking framework for unit tests
- **TestContainers** - Integration testing with real databases
- **Spring Boot Test** - Integration testing support
- **WireMock** - API mocking for service testing

## Runtime Requirements

- **Java 21+** - Required runtime version
- **4GB+ RAM** - Recommended for development (multiple services)
- **Docker & Docker Compose** - For containerized development
- **PostgreSQL/MySQL** - For production deployments
- **Message Broker** - Kafka or RabbitMQ for event processing