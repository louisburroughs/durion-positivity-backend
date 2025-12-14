# Project Structure

## Root Directory Layout

```java
positivity/
├── pos-api-gateway/           # API Gateway service
├── pos-service-discovery/     # Service discovery (Eureka)
├── pos-security-service/      # Centralized authentication/authorization
├── pos-catalog/              # Product catalog management
├── pos-customer/             # Customer management
├── pos-inventory/            # General inventory management
├── pos-vehicle-inventory/    # Vehicle-specific inventory
├── pos-order/                # Order processing
├── pos-invoice/              # Invoicing and billing
├── pos-price/                # Pricing management
├── pos-accounting/           # Financial and accounting
├── pos-work-order/           # Work order management
├── pos-people/               # Staff and organizational management
├── pos-location/             # Location and geographic services
├── pos-events/               # Event publishing
├── pos-event-receiver/       # Event consumption and processing
├── pos-image/                # Image and media management
├── pos-vehicle-fitment/      # Vehicle parts fitment
├── pos-vehicle-reference-nhtsa/  # NHTSA vehicle data integration
├── pos-vehicle-reference-carapi/ # CarAPI vehicle data integration
├── pos-inquiry/              # Customer inquiries and support
├── pos-shop-manager/         # Shop management functionality
├── docker-compose.yml        # Multi-service development environment
├── pom.xml                   # Root Maven configuration
└── .kiro/                    # Kiro AI assistant configuration
```

## Microservice Architecture

Each service follows Spring Boot microservice structure:

```
pos-{service-name}/
├── src/main/java/com/positivity/{service}/
│   ├── {Service}Application.java     # Spring Boot main class
│   ├── controller/                   # REST API controllers
│   │   └── {Domain}Controller.java
│   ├── service/                      # Business logic services
│   │   ├── {Domain}Service.java
│   │   └── impl/
│   │       └── {Domain}ServiceImpl.java
│   ├── repository/                   # Data access layer
│   │   └── {Domain}Repository.java
│   ├── model/                        # JPA entities and DTOs
│   │   ├── entity/
│   │   │   └── {Domain}Entity.java
│   │   └── dto/
│   │       └── {Domain}DTO.java
│   ├── config/                       # Service configuration
│   │   ├── SecurityConfig.java
│   │   ├── DatabaseConfig.java
│   │   └── {Service}Config.java
│   └── exception/                    # Custom exceptions
│       └── {Service}Exception.java
├── src/main/resources/
│   ├── application.yml               # Service configuration
│   ├── bootstrap.yml                 # Service discovery config
│   └── db/migration/                 # Database migrations (Flyway)
├── src/test/java/                    # Unit and integration tests
├── Dockerfile                        # Container build instructions
└── pom.xml                          # Service-specific dependencies
```

## Core Infrastructure Services

### API Gateway (pos-api-gateway)

- **Purpose**: Single entry point for all client requests
- **Technology**: Spring Cloud Gateway
- **Key Features**: Routing, load balancing, authentication, rate limiting
- **Configuration**: Routes to all business services

### Service Discovery (pos-service-discovery)

- **Purpose**: Service registration and discovery
- **Technology**: Spring Cloud Netflix Eureka
- **Key Features**: Health checks, load balancing, failover

### Security Service (pos-security-service)

- **Purpose**: Centralized authentication and authorization
- **Technology**: Spring Security, JWT
- **Key Features**: User management, role-based access control, JWT token validation

## Business Domain Services

### Product & Catalog Services

- **pos-catalog** - Product catalog, categories, pricing rules
- **pos-inventory** - Stock management, warehouse operations
- **pos-vehicle-inventory** - Automotive-specific inventory tracking

### Customer & Order Services

- **pos-customer** - Customer profiles, preferences, history
- **pos-order** - Order lifecycle, cart management, fulfillment
- **pos-invoice** - Billing, payment processing, receipts

### Automotive Services

- **pos-vehicle-fitment** - Parts compatibility and fitment data
- **pos-vehicle-reference-nhtsa** - NHTSA vehicle database integration
- **pos-vehicle-reference-carapi** - CarAPI vehicle data integration

### Operations Services

- **pos-work-order** - Service scheduling, task management
- **pos-people** - Staff management, roles, permissions
- **pos-location** - Multi-location support, geographic services
- **pos-shop-manager** - Shop operations and management

### Support Services

- **pos-inquiry** - Customer support, ticketing system
- **pos-accounting** - Financial reporting, accounting integration
- **pos-price** - Dynamic pricing, promotions, discounts
- **pos-image** - Media management, image processing

### Event Services

- **pos-events** - Event publishing and message routing
- **pos-event-receiver** - Event consumption and processing

## Configuration Management

### Service Configuration

- **application.yml** - Service-specific settings (port, database, etc.)
- **bootstrap.yml** - Service discovery and config server settings
- **Environment Variables** - Runtime configuration overrides

### Database Configuration

- Each service maintains its own database schema
- Connection pooling and transaction management per service
- Database migrations managed via Flyway or Liquibase

### Security Configuration

- JWT token validation in each service
- Role-based access control at controller level
- Service-to-service authentication via service tokens

## Development Patterns

### Adding New Services

1. Create new Maven module in root directory
2. Follow standard Spring Boot microservice structure
3. Add service registration to Eureka
4. Configure API Gateway routing
5. Implement security integration
6. Add to docker-compose.yml for development

### API Development

- RESTful endpoints following OpenAPI 3.0 specification
- Consistent error handling and response formats
- Request/response validation using Bean Validation
- API versioning strategy for backward compatibility

### Data Management

- JPA entities with proper relationships
- Repository pattern for data access
- Database per service principle
- Event-driven data synchronization between services

### Testing Strategy

- Unit tests for business logic (service layer)
- Integration tests for API endpoints
- Contract testing between services
- End-to-end testing via TestContainers

### Deployment Patterns

- Docker containers for each service
- Docker Compose for local development
- Kubernetes manifests for production deployment
- Health checks and readiness probes
- Centralized logging and monitoring

## File Naming Conventions

### Java Classes

- **Controllers**: `{Domain}Controller.java`
- **Services**: `{Domain}Service.java`, `{Domain}ServiceImpl.java`
- **Entities**: `{Domain}Entity.java` or `{Domain}.java`
- **DTOs**: `{Domain}DTO.java`, `{Domain}Request.java`, `{Domain}Response.java`
- **Repositories**: `{Domain}Repository.java`

### Configuration Files

- **Service Config**: `application.yml`, `application-{profile}.yml`
- **Docker**: `Dockerfile`, `docker-compose.yml`
- **Maven**: `pom.xml`
- **Database**: `V{version}__{description}.sql` (Flyway migrations)

### API Endpoints

- **REST Resources**: `/api/v1/{domain}/{resource}`
- **Health Checks**: `/actuator/health`
- **Metrics**: `/actuator/metrics`
- **Service Info**: `/actuator/info`
