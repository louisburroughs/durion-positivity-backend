# Positivity POS System - Steering Summary

## Overview

This document consolidates all steering guidance for the Positivity POS system development. It provides a comprehensive reference for agents, developers, and stakeholders working on the Spring Boot microservices architecture.

## Product Overview

**Positivity** is a modular Point of Sale (POS) system designed for infinite scaling with a "pay as you grow" model. Built using Spring Boot microservices architecture, it provides enterprise-grade functionality for retail, automotive, and service-based businesses.

### Core Business Domains
- **Catalog & Inventory** - Product management, stock tracking, vehicle-specific inventory
- **Customer & Orders** - Customer profiles, order processing, invoicing, payments
- **Automotive Services** - Vehicle fitment, parts compatibility, reference data integration
- **Operations** - Work orders, staff management, location services, shop operations
- **Support & Analytics** - Customer inquiries, accounting, pricing, media management

## Technology Stack

### Core Framework
- **Spring Boot 3.2.6** - Primary microservices framework
- **Spring Cloud 2023.0.1** - Microservices infrastructure
- **Java 21** - Modern runtime with latest features
- **Maven** - Build system and dependency management

### Infrastructure
- **Spring Cloud Gateway** - API Gateway for routing
- **Spring Cloud Netflix Eureka** - Service discovery
- **Spring Security + JWT** - Authentication and authorization
- **PostgreSQL/MySQL** - Production databases (database per service)
- **Apache Kafka/RabbitMQ** - Event streaming and messaging

### Development & Deployment
- **Docker + Docker Compose** - Containerization and orchestration
- **Spring Boot Actuator** - Health checks and metrics
- **Micrometer + Prometheus** - Observability and monitoring
- **JUnit 5 + TestContainers** - Testing framework

## Project Structure

### Root Layout
```
positivity/
├── pos-api-gateway/           # API Gateway service
├── pos-service-discovery/     # Service discovery (Eureka)
├── pos-security-service/      # Centralized authentication
├── pos-catalog/              # Product catalog management
├── pos-customer/             # Customer management
├── pos-inventory/            # General inventory
├── pos-vehicle-inventory/    # Vehicle-specific inventory
├── pos-order/                # Order processing
├── pos-invoice/              # Invoicing and billing
├── pos-price/                # Pricing management
├── pos-accounting/           # Financial and accounting
├── pos-work-order/           # Work order management
├── pos-people/               # Staff management
├── pos-location/             # Location services
├── pos-events/               # Event publishing
├── pos-event-receiver/       # Event consumption
├── pos-image/                # Media management
├── pos-vehicle-fitment/      # Vehicle parts fitment
├── pos-vehicle-reference-*/  # Vehicle data integration
├── pos-inquiry/              # Customer support
├── pos-shop-manager/         # Shop operations
├── docker-compose.yml        # Development environment
└── pom.xml                   # Root Maven configuration
```

### Microservice Structure
Each service follows standard Spring Boot patterns:
- **Controller Layer** - REST API endpoints
- **Service Layer** - Business logic implementation
- **Repository Layer** - Data access with Spring Data JPA
- **Model Layer** - JPA entities and DTOs
- **Configuration** - Security, database, service-specific config

## Agent Integration Guide

### Core Development Agents

#### **microservices-architect** - System Architect
- **Purpose**: Service boundaries, integration patterns, system design
- **Use For**: Service decomposition, inter-service communication, architectural decisions

#### **spring-boot-developer** - Primary Implementation Agent
- **Purpose**: Spring Boot microservice development and implementation
- **Pairing**: Works with **spring-boot-pair-navigator** for loop detection and quality
- **Use For**: Service implementation, REST APIs, business logic, data access

#### **spring-boot-pair-navigator** - Pairing & Quality Agent
- **Purpose**: Loop detection, architectural drift prevention, simplification guidance
- **Stop-Phrases**: Uses mandatory interruption phrases for problematic patterns
- **Use For**: Complex implementations, refactoring decisions, quality improvement

#### **api-gateway-specialist** - Gateway Expert
- **Purpose**: API Gateway configuration, routing, cross-cutting concerns
- **Use For**: Gateway routing, authentication filters, API management

### Infrastructure & Operations Agents

#### **containerization-specialist** - DevOps Engineer
- **Purpose**: Docker containerization, orchestration, deployment
- **Use For**: Container optimization, Docker Compose, Kubernetes deployment

#### **database-per-service-specialist** - Database Expert
- **Purpose**: Database design per service, data consistency patterns
- **Use For**: Schema design, migrations, data consistency, performance optimization

#### **observability-engineer** - Monitoring & Reliability
- **Purpose**: Microservices monitoring, tracing, reliability
- **Use For**: Distributed tracing, metrics collection, monitoring setup

### Quality & Security Agents

#### **microservices-testing-specialist** - Testing Expert
- **Purpose**: Microservices testing strategy and implementation
- **Use For**: Unit tests, integration tests, contract testing, TestContainers

#### **security-specialist** - Security Expert
- **Purpose**: JWT authentication, Spring Security, service-to-service security
- **Use For**: Authentication implementation, security configuration, token management

#### **event-driven-specialist** - Event Architecture Expert
- **Purpose**: Event-driven architecture, messaging, async communication
- **Use For**: Event schemas, Kafka/RabbitMQ patterns, event sourcing

## Development Instructions Integration

### Technology-Specific Instructions

#### **spring-boot-microservices.instructions**
- **Scope**: `**/*.java`, `**/application.yml`, `**/pom.xml`
- **Guidelines**: Java 21 features, Spring Boot conventions, dependency injection, JPA, error handling

#### **spring-cloud-patterns.instructions**
- **Scope**: `**/*.java`, `**/bootstrap.yml`, `**/docker-compose.yml`
- **Guidelines**: Service discovery, API Gateway, circuit breakers, distributed config, tracing

#### **jpa-hibernate.instructions**
- **Scope**: `**/entity/*.java`, `**/repository/*.java`, `**/migration/*.sql`
- **Guidelines**: JPA entities, repository patterns, migrations, database-per-service, query optimization

#### **angular-frontend.instructions**
- **Scope**: `**/*.ts`, `**/*.html`, `**/*.scss`
- **Guidelines**: Angular 17+, reactive forms, Material UI, NgRx, accessibility

### Cross-Cutting Instructions

#### **microservices-security.instructions**
- **Scope**: All files
- **Guidelines**: JWT authentication, service-to-service security, HTTPS, input validation, secrets management

#### **microservices-performance.instructions**
- **Scope**: All files
- **Guidelines**: Connection pooling, async processing, caching, database optimization, event-driven architecture

#### **spring-boot-quality.instructions**
- **Scope**: All files
- **Guidelines**: Spring Boot conventions, testing, static analysis, exception handling, logging, clean code

### Specialized Instructions

#### **event-driven-architecture.instructions**
- **Guidelines**: Event schemas, idempotent handlers, Kafka/RabbitMQ, error handling, event sourcing

#### **containerization.instructions**
- **Guidelines**: Docker optimization, health checks, Kubernetes, security, resource management

#### **api-documentation.instructions**
- **Guidelines**: OpenAPI 3.0, API versioning, examples, Spring REST Docs

#### **automotive-domain.instructions**
- **Guidelines**: Vehicle fitment, pricing scenarios, work orders, customer hierarchies

## Agent Collaboration Patterns

### Primary Development Workflow

1. **Service Architecture** (`microservices-architect`)
   - Define service boundaries and responsibilities
   - Design inter-service communication patterns
   - Create integration strategies

2. **Service Implementation** (`spring-boot-developer` + `spring-boot-pair-navigator`)
   - Implement Spring Boot microservices with pairing
   - Create JPA entities, repositories, and services
   - Develop REST APIs with validation and error handling

3. **API Gateway Configuration** (`api-gateway-specialist`)
   - Configure routing and load balancing
   - Implement authentication and authorization
   - Manage API versioning

4. **Security Integration** (`security-specialist`)
   - Implement JWT-based authentication
   - Configure service-to-service security
   - Ensure secure communication patterns

5. **Event-Driven Integration** (`event-driven-specialist`)
   - Design event schemas and messaging
   - Implement async communication
   - Handle event consistency and errors

6. **Quality & Testing** (`microservices-testing-specialist`, `spring-boot-quality-enforcer`)
   - Create comprehensive test suites
   - Enforce Spring Boot best practices
   - Generate API documentation

7. **Deployment & Monitoring** (`containerization-specialist`, `observability-engineer`)
   - Build and containerize services
   - Set up monitoring and tracing
   - Manage development environments

## Best Practices Summary

### Service Design
- Follow database-per-service principle
- Use event-driven communication between services
- Implement proper service boundaries and domain isolation
- Design for failure with circuit breakers and timeouts

### Security
- Implement JWT authentication with Spring Security
- Use service-to-service authentication with JWT tokens
- Enforce HTTPS communication between all services
- Follow OWASP guidelines for API security

### Performance
- Implement connection pooling for databases and HTTP clients
- Use async processing with Spring WebFlux where appropriate
- Implement proper caching strategies (Redis, Caffeine)
- Monitor and optimize database queries and indexes

### Quality
- Follow Spring Boot coding conventions and best practices
- Implement comprehensive unit and integration testing
- Use static code analysis with SonarQube
- Ensure proper exception handling and structured logging

### Deployment
- Create optimized Docker images with multi-stage builds
- Implement proper health checks and readiness probes
- Use Kubernetes ConfigMaps and Secrets for configuration
- Follow container security best practices

## Quick Reference Commands

### Development
```bash
# Build all services
mvn clean install

# Start development environment
docker-compose up -d

# Run specific service tests
mvn test -pl pos-catalog

# Build Docker images
mvn clean package spring-boot:build-image
```

### Service Management
```bash
# View service logs
docker-compose logs -f pos-api-gateway

# Restart specific service
docker-compose restart pos-inventory

# Clean restart all services
docker-compose down -v && docker-compose up -d
```

This steering summary provides comprehensive guidance for all aspects of Positivity POS system development, from architecture and implementation to testing and deployment. Reference this document for consistent development practices across all microservices.