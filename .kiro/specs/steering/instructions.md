# Development Instructions Integration

## Overview

The Positivity POS system follows comprehensive development instructions for Spring Boot microservices architecture. These instructions provide detailed guidance for building scalable, secure, and maintainable microservices using modern Java and Spring technologies.

## Available Instructions

### Technology-Specific Instructions

#### **spring-boot-microservices.instructions** - Spring Boot Development

- **Scope**: `**/*.java`, `**/application.yml`, `**/pom.xml`
- **Key Guidelines**:
  - Use Java 21 features (Records, Pattern Matching, Virtual Threads)
  - Follow Spring Boot conventions for package structure and naming
  - Implement proper dependency injection with constructor injection
  - Use Spring Data JPA with proper entity relationships
  - Implement comprehensive error handling with @ControllerAdvice
  - Follow REST API conventions with proper HTTP status codes
- **Integration**: Apply to all Spring Boot microservice development

#### **spring-cloud-patterns.instructions** - Microservices Patterns

- **Scope**: `**/*.java`, `**/bootstrap.yml`, `**/docker-compose.yml`
- **Key Guidelines**:
  - Implement service discovery with Eureka registration
  - Use Spring Cloud Gateway for API routing and filtering
  - Implement circuit breaker patterns with Resilience4j
  - Use distributed configuration with Spring Cloud Config
  - Implement distributed tracing with Micrometer and Zipkin
- **Integration**: Apply when implementing cross-cutting microservices concerns

#### **jpa-hibernate.instructions** - Data Persistence

- **Scope**: `**/entity/*.java`, `**/repository/*.java`, `**/migration/*.sql`
- **Key Guidelines**:
  - Design entities with proper JPA annotations and relationships
  - Use repository pattern with Spring Data JPA
  - Implement database migrations with Flyway
  - Follow database-per-service principle
  - Optimize queries and implement proper indexing strategies
- **Integration**: Apply to all data access layer development

#### **angular-frontend.instructions** - Frontend Development

- **Scope**: `**/*.ts`, `**/*.html`, `**/*.scss`, `**/*.json`
- **Key Guidelines**:
  - Use Angular 17+ with standalone components
  - Implement reactive forms with proper validation
  - Use Angular Material for consistent UI components
  - Implement proper state management with NgRx
  - Follow accessibility guidelines (WCAG 2.1)
  - Implement proper error handling and loading states
- **Integration**: Apply when developing POS frontend interfaces

### Cross-Cutting Instructions

#### **microservices-security.instructions** - Security Guidelines

- **Scope**: All files (`*`)
- **Key Guidelines**:
  - Implement JWT authentication with Spring Security
  - Use service-to-service authentication with JWT tokens
  - Enforce HTTPS communication between all services
  - Implement proper input validation and sanitization
  - Use secrets management (Kubernetes secrets, HashiCorp Vault)
  - Follow OWASP guidelines for API security
- **Integration**: Apply to all microservices, especially authentication and data handling

#### **microservices-performance.instructions** - Performance Best Practices

- **Scope**: All files (`*`)
- **Key Guidelines**:
  - Implement connection pooling for database and HTTP clients
  - Use async processing with Spring WebFlux where appropriate
  - Implement proper caching strategies (Redis, Caffeine)
  - Monitor and optimize database queries and indexes
  - Use event-driven architecture for loose coupling
  - Implement proper timeout and retry mechanisms
- **Integration**: Apply during performance-critical implementations and optimization

#### **spring-boot-quality.instructions** - Code Quality Standards

- **Scope**: All files (`**`)
- **Key Guidelines**:
  - Follow Spring Boot coding conventions and best practices
  - Implement comprehensive unit and integration testing
  - Use SonarQube for static code analysis
  - Ensure proper exception handling with custom exceptions
  - Implement proper logging with structured logging (JSON)
  - Follow clean code principles and SOLID design patterns
- **Integration**: Use during all code reviews and quality assessments

### Specialized Instructions

#### **event-driven-architecture.instructions** - Event Processing

- **Scope**: `**/events/*.java`, `**/messaging/*.java`, `**/*EventHandler.java`
- **Purpose**: Event-driven communication between microservices
- **Key Guidelines**:
  - Design event schemas with proper versioning
  - Implement idempotent event handlers
  - Use Kafka or RabbitMQ for reliable message delivery
  - Implement proper error handling and dead letter queues
  - Follow event sourcing patterns where appropriate
- **Integration**: Use for inter-service communication and async processing

#### **containerization.instructions** - Docker & Kubernetes

- **Scope**: `**/Dockerfile`, `**/docker-compose.yml`, `**/*.yaml`
- **Purpose**: Container deployment and orchestration
- **Key Guidelines**:
  - Create optimized Docker images with multi-stage builds
  - Implement proper health checks and readiness probes
  - Use Kubernetes ConfigMaps and Secrets for configuration
  - Follow container security best practices
  - Implement proper resource limits and requests
- **Integration**: Apply for all service containerization and deployment

#### **api-documentation.instructions** - OpenAPI Documentation

- **Purpose**: Comprehensive API documentation and contracts
- **Key Guidelines**:
  - Use OpenAPI 3.0 annotations for automatic documentation
  - Implement API versioning strategies
  - Provide comprehensive examples and error responses
  - Use Spring REST Docs for test-driven documentation
- **Integration**: Apply when creating or updating REST APIs

#### **automotive-domain.instructions** - POS Domain Patterns

- **Purpose**: Automotive and retail domain-specific patterns
- **Key Guidelines**:
  - Implement vehicle fitment and compatibility logic
  - Handle complex pricing and inventory scenarios
  - Manage work order lifecycles and scheduling
  - Implement customer and location hierarchies
- **Integration**: Apply when implementing domain-specific business logic

## Integration Strategies

### 1. **Spec Creation Integration**

When creating Kiro specs, reference relevant instructions:

```markdown
## Technical Requirements
- Follow `spring-boot-microservices.instructions` for service implementation
- Apply `microservices-security.instructions` for authentication and authorization
- Use `microservices-performance.instructions` for scalability and optimization
- Implement according to `angular-frontend.instructions` for POS interfaces
- Follow `event-driven-architecture.instructions` for inter-service communication
```

### 2. **Task Execution Integration**

When executing spec tasks, apply instruction guidelines:

- **Service Architecture**: Reference `spring-cloud-patterns.instructions` for microservices patterns
- **Implementation Tasks**: Apply `spring-boot-microservices.instructions` and `jpa-hibernate.instructions`
- **API Tasks**: Follow `microservices-security.instructions` and `api-documentation.instructions`
- **Frontend Tasks**: Use `angular-frontend.instructions` for POS interface development
- **Event Processing**: Apply `event-driven-architecture.instructions` for async communication
- **Deployment Tasks**: Use `containerization.instructions` for Docker and Kubernetes

### 3. **Code Review Integration**

Use instruction guidelines during code reviews:

1. **Security Review**: Apply `security-and-owasp.instructions.md` checklist
2. **Performance Review**: Use `performance-optimization.instructions.md` guidelines
3. **Language Review**: Apply relevant language-specific instructions
4. **Quality Review**: Follow `code-review-generic.instructions.md` standards

### 4. **Agent Coordination Integration**

Coordinate instructions with microservices agents:

| Agent | Primary Instructions | Supporting Instructions |
|-------|---------------------|------------------------|
| **spring-boot-developer** | `spring-boot-microservices.instructions`, `jpa-hibernate.instructions` | `microservices-security.instructions`, `microservices-performance.instructions` |
| **api-gateway-specialist** | `spring-cloud-patterns.instructions`, `microservices-security.instructions` | `api-documentation.instructions`, `microservices-performance.instructions` |
| **microservices-architect** | `spring-cloud-patterns.instructions`, `event-driven-architecture.instructions` | `microservices-performance.instructions`, `spring-boot-quality.instructions` |
| **security-specialist** | `microservices-security.instructions` | `spring-boot-microservices.instructions`, `containerization.instructions` |
| **containerization-specialist** | `containerization.instructions` | `microservices-security.instructions`, `microservices-performance.instructions` |

## Instruction Application Patterns

### 1. **By File Type**

Apply instructions based on file extensions:

```
*.java → spring-boot-microservices.instructions + microservices-security.instructions
application.yml → spring-cloud-patterns.instructions + microservices-performance.instructions
*.ts → angular-frontend.instructions + api-documentation.instructions
Dockerfile → containerization.instructions + microservices-security.instructions
*EventHandler.java → event-driven-architecture.instructions + microservices-performance.instructions
```

### 2. **By Development Phase**

Apply instructions based on development phase:

- **Architecture**: `spring-cloud-patterns.instructions`, `event-driven-architecture.instructions`
- **Implementation**: `spring-boot-microservices.instructions` + `microservices-security.instructions`
- **Testing**: `spring-boot-quality.instructions` + service-specific testing guidelines
- **Review**: `spring-boot-quality.instructions` + `microservices-performance.instructions`
- **Deployment**: `containerization.instructions` + `microservices-security.instructions`

### 3. **By Service Type**

Apply instructions based on microservice type:

- **API Gateway**: `spring-cloud-patterns.instructions` + `microservices-security.instructions`
- **Business Services**: `spring-boot-microservices.instructions` + `jpa-hibernate.instructions`
- **Event Services**: `event-driven-architecture.instructions` + `microservices-performance.instructions`
- **Security Service**: `microservices-security.instructions` + `spring-boot-microservices.instructions`
- **Frontend**: `angular-frontend.instructions` + `api-documentation.instructions`

## Quality Assurance Integration

### Automated Checks

Integrate instruction compliance into automated checks:

1. **Static Analysis**: Configure tools to enforce instruction guidelines
2. **Code Review**: Use instruction checklists during reviews
3. **CI/CD**: Include instruction compliance in build pipelines
4. **Documentation**: Generate compliance reports based on instruction adherence

### Manual Reviews

Use instructions during manual code reviews:

1. **Security Review**: Apply OWASP guidelines from `security-and-owasp.instructions.md`
2. **Performance Review**: Use optimization patterns from `performance-optimization.instructions.md`
3. **Code Quality**: Follow standards from `code-review-generic.instructions.md`
4. **Language Compliance**: Check against language-specific instructions

## Best Practices for Instruction Integration

### 1. **Consistent Application**

- Always reference relevant instructions when starting new development work
- Use instruction guidelines as checklists during implementation
- Apply instructions consistently across all team members and projects

### 2. **Contextual Usage**

- Select appropriate instructions based on the specific task and technology
- Combine multiple instructions when working on cross-cutting concerns
- Prioritize security and performance instructions for critical components

### 3. **Continuous Improvement**

- Update instructions based on lessons learned and new best practices
- Regularly review instruction compliance and effectiveness
- Integrate feedback from code reviews and quality assessments

### 4. **Documentation Integration**

- Reference specific instructions in spec requirements and design decisions
- Document instruction compliance in implementation notes
- Include instruction references in code comments where appropriate

## Instruction Compliance Checklist

When completing microservices development work, verify compliance with relevant instructions:

- [ ] **Security**: Applied `microservices-security.instructions` guidelines
- [ ] **Performance**: Followed `microservices-performance.instructions` best practices
- [ ] **Spring Boot Standards**: Adhered to `spring-boot-microservices.instructions` guidelines
- [ ] **Code Quality**: Met `spring-boot-quality.instructions` standards
- [ ] **API Documentation**: Used `api-documentation.instructions` for REST APIs
- [ ] **Containerization**: Followed `containerization.instructions` for deployment
- [ ] **Event Processing**: Applied `event-driven-architecture.instructions` for async communication
- [ ] **Data Persistence**: Used `jpa-hibernate.instructions` for database operations

## Integration with Kiro Workflow

### Spec Creation

1. **Requirements Phase**: Reference security and performance instructions for non-functional requirements
2. **Design Phase**: Apply architectural and language-specific instructions for technical design
3. **Task Planning**: Include instruction compliance as task acceptance criteria

### Task Execution

1. **Service Implementation**: Apply Spring Boot and microservices instructions
2. **Testing**: Use Spring Boot quality instructions for comprehensive testing
3. **API Development**: Follow API documentation and security instructions
4. **Event Processing**: Apply event-driven architecture instructions
5. **Deployment**: Follow containerization and security instructions

### Quality Assurance

1. **Code Review**: Use Spring Boot quality instruction checklists
2. **Performance Testing**: Apply microservices performance guidelines
3. **Security Audit**: Follow microservices security instruction guidelines
4. **Architecture Review**: Validate against Spring Cloud patterns instructions

This integration ensures that all microservices development follows Spring Boot best practices, maintains consistency across services, and meets scalability, security, and performance standards for the Positivity POS system.
