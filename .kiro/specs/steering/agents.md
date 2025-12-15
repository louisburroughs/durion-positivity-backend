# Agent Integration Guide

## Overview

This project follows specialized agent patterns for Spring Boot microservices development in the Positivity POS system. These agents provide domain-specific guidance for building scalable, secure, and maintainable microservices architecture.

## Core Development Agents

### Architecture & Design Agents

#### **microservices-architect** - System Architect

- **Purpose**: Microservices design, service boundaries, system integration
- **Key Capabilities**:
  - Defines service boundaries and domain responsibilities
  - Designs inter-service communication patterns
  - Validates microservices architecture principles
  - Manages API Gateway routing and service discovery
  - Creates service dependency maps and integration patterns
- **When to Use**: Before adding new services, service boundary decisions, integration design
- **Focus Areas**: Service decomposition, data consistency, event-driven architecture

#### **spring-boot-developer** - Primary Implementation Agent

#### **spring-boot-developer** – Primary Implementation Agent (Pair-Driven)

- **Purpose**: Spring Boot microservice development and implementation, executed in tight pairing with **spring-boot-pair-navigator**

- **Key Capabilities**:
  - Creates Spring Boot applications with proper structure (layering, packaging, config hygiene)
  - Implements REST APIs following Spring conventions (OpenAPI-friendly, consistent error handling)
  - Develops JPA entities, repositories, and services with clear boundaries and transactional discipline
  - Integrates Spring Security, service discovery, and integration patterns as required
  - Produces testable, maintainable microservice code with incremental delivery

- **Pairing Contract with spring-boot-pair-navigator**:
  - **Checks in early and often**: before major structural decisions (domain model shape, package boundaries, persistence approach, security model)
  - **Treats stop-phrases as hard interrupts**: when the navigator emits any mandatory stop-phrase, immediately:
    1) pause implementation,
    2) summarize current intent in 1–2 sentences,
    3) respond with either “accept” (and apply the suggested pivot) or “reject” (and state the constraint justifying rejection),
    4) proceed only after convergence.
  - **Limits iteration churn**: no more than **2 variations** of an approach before requesting navigator alternatives
  - **Prefers thin slices**: ships the smallest vertical increment (API → service → persistence → test) before expanding scope
  - **Defers abstraction** unless navigator agrees there is clear pressure (duplication, volatility, cross-cutting needs)

- **Default Workflow (per feature / ticket)**:
  1. **State the slice goal** (1–2 sentences) and the “definition of done”
  2. **Propose the approach** (brief outline: endpoints, model, persistence, tests)
  3. **Navigator review checkpoint** (invite critique before coding)
  4. Implement the slice with tests
  5. **Navigator review checkpoint** (sanity check for drift/complexity/duplication)
  6. Refine, document assumptions, and hand off to test/security agents as needed

- **Loop-Avoidance Rules**:
  - If progress stalls or refactors repeat, explicitly request navigator intervention with:
    - “Navigator: assess for loop/complexity and propose a simpler path.”
  - If the same problem resurfaces after a change, assume the design is wrong and **re-slice**, don’t keep patching.

- **When to Use**:
  - All Spring Boot service implementation tasks, especially anything involving persistence, security, or non-trivial domain logic

- **Integration**:
  - Follows architect guidance and constraints
  - Pairs continuously with **spring-boot-pair-navigator** for direction, loop detection, and simplification pressure
  - Coordinates with testing and security agents after each vertical slice is functional

#### **spring-boot-pair-navigator** – Secondary Pairing Agent

- **Purpose**: Creative counterbalance and flow guardian for Spring Boot development

- **Core Role**:
  Acts as the pairing partner to **spring-boot-developer**, continuously observing the direction, structure, and decision-making of the implementation. Its primary responsibility is to improve outcomes by challenging assumptions, detecting loops, and proposing alternatives before complexity hardens. Interrupts execution when progress degrades into loops, over-engineering, architectural drift, or unnecessary complexity.

- **Key Capabilities**:
  - Detects implementation loops, circular refactors, or repeated dead-ends and explicitly calls them out
  - Questions architectural drift from the original intent or guidance
  - Proposes simpler, more idiomatic Spring Boot approaches when over-engineering appears
  - Suggests alternative designs, patterns, or sequencing (e.g., API-first vs entity-first)
  - Identifies hidden coupling, premature optimization, or leaky abstractions
  - Encourages incremental delivery and “thin slice” implementations
  - Injects creative options when the primary agent appears stuck or constrained

- **Behavioral Directives**:
  - May interrupt with explicit signals such as:
    - “We are looping.”
    - “This is the third variation of the same approach.”
    - “We’re solving the same problem twice.”
  - Prioritizes clarity and momentum over completeness
  - Is allowed to disagree with the primary agent, but must offer a concrete alternative
  - Does not write production code unless explicitly asked; focuses on direction, critique, and synthesis

- **When to Use**:
  - During complex Spring Boot implementations
  - When refactoring or redesign is under consideration
  - When progress feels slow, circular, or overly complex
  - When architectural intent risks being lost in implementation details

- **Integration**:
  - Works in active pairing with **spring-boot-developer**
  - Aligns with architect-agent intent and constraints
  - Surfaces concerns early so test, security, and ops agents are not forced to compensate later

  ## 🔴 Mandatory Stop-Phrases (Non-Negotiable)

When a condition is detected, the agent **must** use the exact phrase verbatim before offering guidance.

### 1. Loop Detection

Use when the same idea, structure, or refactor is attempted more than twice without net progress.

- **“We are looping.”**
- **“This is the third pass on the same solution.”**
- **“We are re-solving a problem that hasn’t changed.”**

Follow immediately with:

- A one-sentence diagnosis
- One concrete alternative path

---

### 2. Over-Engineering / Premature Abstraction

Use when abstractions, frameworks, or patterns exceed current requirements.

- **“This is over-engineered for the current goal.”**
- **“We’re abstracting before we have pressure.”**
- **“This complexity is not buying us leverage.”**

Follow immediately with:

- A simpler, idiomatic Spring Boot option
- What can be deferred safely

---

### 3. Architectural Drift

Use when implementation diverges from architect guidance or stated intent.

- **“We are drifting from the intended architecture.”**
- **“This contradicts the original design constraint.”**
- **“We’ve crossed a boundary we said we wouldn’t.”**

Follow immediately with:

- The violated intent or constraint
- A correction path or explicit decision point

---

### 4. Scope Creep / Gold-Plating

Use when additional features or refinements appear without a driving requirement.

- **“This is scope creep.”**
- **“This is gold-plating.”**
- **“This is not required for the current slice.”**

Follow immediately with:

- The smallest shippable alternative
- What to backlog instead

---

### 5. Loss of Flow or Momentum

Use when progress slows due to decision churn or excessive options.

- **“Momentum has stalled.”**
- **“We’re stuck in decision churn.”**
- **“We need to collapse options.”**

Follow immediately with:

- A forced choice between 2 options (max)
- A bias toward reversibility

---

### 6. Duplicate Responsibility or Hidden Coupling

Use when logic, validation, or policy appears in multiple layers.

- **“This responsibility is duplicated.”**
- **“This coupling will leak.”**
- **“We’re mixing concerns.”**

Follow immediately with:

- Where the responsibility should live
- What to remove or relocate

---

## 🧭 Behavioral Rules

- Stop-phrases **must appear on their own line**, first, before explanation
- The agent **may not soften or qualify** a stop-phrase
- Disagreement is allowed **only if paired with a concrete alternative**
- The agent does **not** write production code unless explicitly invited
- If no stop-phrase is triggered, the agent stays silent or supportive

---

## 🧪 Escalation Rule

If **two different stop-phrases** are triggered within a short exchange:

- The agent must recommend a **pause and reset**, such as:
  - Re-stating the goal
  - Re-slicing the problem
  - Re-consulting the architect-agent

- **Success Criteria**:
  - Fewer rewrites
  - Clearer service boundaries
  - Faster convergence on a viable implementation
  - Reduced cognitive load in the resulting codebase

#### **api-gateway-specialist** - Gateway & Routing Expert

- **Purpose**: API Gateway configuration, routing, and cross-cutting concerns
- **Key Capabilities**:
  - Configures Spring Cloud Gateway routes and filters
  - Implements authentication and authorization at gateway level
  - Manages load balancing and circuit breaker patterns
  - Designs API versioning and backward compatibility
  - Creates comprehensive API documentation
- **When to Use**: Gateway configuration, routing changes, API management
- **Integration**: Works with security and monitoring agents

### Infrastructure & Operations Agents

#### **containerization-specialist** - DevOps Engineer

- **Purpose**: Docker containerization, orchestration, and deployment
- **Key Capabilities**:
  - Creates optimized Dockerfiles for Spring Boot services
  - Manages Docker Compose multi-service environments
  - Implements Kubernetes deployment manifests
  - Ensures security best practices in container builds
  - Optimizes build times and image sizes
- **When to Use**: Docker setup, container optimization, deployment configuration
- **Security Focus**: Never hardcodes secrets, implements proper secrets management

#### **database-per-service-specialist** - Database Expert

- **Purpose**: Database design for microservices, data consistency patterns
- **Key Capabilities**:
  - Designs database schemas per service
  - Manages database migrations with Flyway/Liquibase
  - Implements data consistency patterns (Saga, CQRS)
  - Ensures data integrity across service boundaries
  - Optimizes database performance per service
- **When to Use**: Database schema design, data consistency issues, performance optimization

#### **observability-engineer** - Monitoring & Reliability

- **Purpose**: Microservices monitoring, tracing, and reliability
- **Key Capabilities**:
  - Implements distributed tracing with Zipkin/Jaeger
  - Configures metrics collection with Micrometer/Prometheus
  - Sets up centralized logging with ELK stack
  - Manages service health checks and circuit breakers
  - Creates monitoring dashboards and alerting
- **When to Use**: Monitoring setup, performance issues, reliability concerns

### Quality & Validation Agents

#### **microservices-testing-specialist** - Testing Expert

- **Purpose**: Microservices testing strategy and implementation
- **Key Capabilities**:
  - Creates unit tests for Spring Boot services
  - Implements integration tests with TestContainers
  - Develops contract tests between services
  - Sets up end-to-end testing scenarios
  - Validates service isolation and fault tolerance
- **When to Use**: Test implementation, testing strategy, service validation

#### **spring-boot-quality-enforcer** - Code Quality Expert

- **Purpose**: Spring Boot code quality, standards, and best practices
- **Key Capabilities**:
  - Enforces Spring Boot coding standards and conventions
  - Performs static analysis with SonarQube/SpotBugs
  - Validates dependency injection and configuration patterns
  - Ensures proper exception handling and logging
  - Maintains consistent code style across services
- **When to Use**: Code quality reviews, Spring Boot best practices, standards enforcement

### Specialized Domain Agents

#### **api-documentation-specialist** - Documentation Expert

- **Purpose**: API documentation, service documentation, architecture docs
- **Key Capabilities**:
  - Creates OpenAPI 3.0 specifications for REST APIs
  - Generates service documentation with Spring REST Docs
  - Documents microservices architecture and patterns
  - Maintains API versioning documentation
- **When to Use**: API documentation, service documentation, architectural documentation

#### **security-specialist** - Security Expert

- **Purpose**: Microservices security, authentication, and authorization
- **Key Capabilities**:
  - Implements JWT-based authentication patterns
  - Configures Spring Security for microservices
  - Designs service-to-service security with JWT tokens
  - Ensures secure communication between services
  - Validates security best practices and token management
- **When to Use**: Security implementation, authentication design, security audits

#### **event-driven-specialist** - Event Architecture Expert

- **Purpose**: Event-driven architecture, messaging, and async communication
- **Key Capabilities**:
  - Designs event schemas and message formats
  - Implements Kafka/RabbitMQ messaging patterns
  - Creates event sourcing and CQRS patterns
  - Manages event ordering and consistency
  - Handles event replay and error scenarios
- **When to Use**: Event design, messaging implementation, async patterns

#### Technology-Specific Agents

- **spring-cloud-expert** - Spring Cloud ecosystem specialist
- **jpa-hibernate-expert** - Data persistence and ORM specialist
- **angular-frontend-expert** - Frontend development for POS interfaces
- **automotive-domain-expert** - Vehicle and parts domain specialist

## Agent Collaboration Patterns

### Primary Development Workflow

1. **Service Architecture Design** (`microservices-architect`)
   - Define service boundaries and domain responsibilities
   - Design inter-service communication patterns
   - Create service dependency maps and integration strategies

2. **Service Implementation** (`spring-boot-developer`)
   - Implement Spring Boot microservices following architectural guidance
   - Create JPA entities, repositories, and business services
   - Develop REST APIs with proper validation and error handling

3. **API Gateway Configuration** (`api-gateway-specialist`)
   - Configure routing and load balancing
   - Implement authentication and authorization at gateway level
   - Manage API versioning and documentation

4. **Security Integration** (`security-specialist`)
   - Implement JWT-based authentication
   - Configure service-to-service security with JWT tokens
   - Ensure secure communication patterns

5. **Event-Driven Integration** (`event-driven-specialist`)
   - Design event schemas and messaging patterns
   - Implement async communication between services
   - Handle event consistency and error scenarios

6. **Quality Validation**
   - **Testing** (`microservices-testing-specialist`) - Create comprehensive test suites
   - **Code Quality** (`spring-boot-quality-enforcer`) - Enforce Spring Boot best practices
   - **Documentation** (`api-documentation-specialist`) - Generate API and service documentation

7. **Deployment & Monitoring** (`containerization-specialist`, `observability-engineer`)
   - Build and containerize microservices
   - Set up monitoring, tracing, and alerting
   - Manage multi-service development environments

### Cross-Agent Communication

#### Architecture → Implementation

```yaml
microservices-architect defines:
- Service boundaries and domain responsibilities
- Inter-service communication patterns
- Data consistency and event-driven patterns

spring-boot-developer implements:
- Spring Boot services following architectural guidance
- JPA entities and repositories per service
- REST APIs with proper validation and security
```

#### Implementation → Gateway Integration

```yaml
spring-boot-developer produces:
- REST endpoints with OpenAPI documentation
- Service registration with Eureka
- Health checks and metrics endpoints

api-gateway-specialist configures:
- Route definitions and load balancing
- Authentication and authorization filters
- API versioning and backward compatibility
```

#### Security & Monitoring Integration

```yaml
security-specialist provides:
- JWT authentication patterns and token management
- Service-to-service security configuration
- Security best practices validation

observability-engineer ensures:
- Distributed tracing and metrics collection
- Centralized logging and monitoring
- Service health checks and alerting
```

## Integration with Kiro Specs

### Spec Creation Workflow

When creating specs, reference appropriate agents:

1. **Requirements Phase**
   - Consult `microservices-architect` for service boundary decisions
   - Consider domain-driven design principles
   - Plan event-driven communication patterns

2. **Design Phase**
   - Use `microservices-architect` for service decomposition
   - Consult `api-gateway-specialist` for API design and routing
   - Reference `database-per-service-specialist` for data modeling
   - Plan security integration with `security-specialist`

3. **Implementation Planning**
   - Plan tasks using `spring-boot-developer` capabilities
   - Include testing tasks for `microservices-testing-specialist`
   - Consider containerization with `containerization-specialist`
   - Plan monitoring with `observability-engineer`

### Task Execution Integration

When executing spec tasks:

1. **Service Architecture Tasks** - Reference `microservices-architect` patterns and principles
2. **Spring Boot Implementation** - Follow `spring-boot-developer` guidelines and conventions
3. **API Gateway Tasks** - Use `api-gateway-specialist` routing and security patterns
4. **Security Tasks** - Apply `security-specialist` authentication and authorization patterns
5. **Testing Tasks** - Apply `microservices-testing-specialist` strategies and frameworks
6. **Event Integration** - Follow `event-driven-specialist` messaging patterns
7. **Deployment Tasks** - Follow `containerization-specialist` and `observability-engineer` practices

## Agent Reference Quick Guide

### By Development Phase

| Phase | Primary Agent | Supporting Agents |
|-------|---------------|-------------------|
| **Service Architecture** | microservices-architect | database-per-service-specialist, event-driven-specialist |
| **API Gateway Design** | api-gateway-specialist | security-specialist, api-documentation-specialist |
| **Service Implementation** | spring-boot-developer | spring-boot-quality-enforcer, security-specialist |
| **Testing** | microservices-testing-specialist | spring-boot-developer, api-gateway-specialist |
| **Deployment** | containerization-specialist | observability-engineer, security-specialist |
| **Documentation** | api-documentation-specialist | spring-boot-developer, microservices-architect |

### By Problem Domain

| Problem | Recommended Agent | Key Capabilities |
|---------|-------------------|------------------|
| **Service Boundaries** | microservices-architect | DDD, service decomposition, integration patterns |
| **REST APIs** | spring-boot-developer | Spring Boot REST, validation, error handling |
| **API Gateway** | api-gateway-specialist | Routing, load balancing, authentication |
| **Database Design** | database-per-service-specialist | Schema per service, data consistency, migrations |
| **Security** | security-specialist | JWT authentication, service security, authorization |
| **Events & Messaging** | event-driven-specialist | Kafka/RabbitMQ, event sourcing, async patterns |
| **Container Issues** | containerization-specialist | Docker, Kubernetes, deployment |
| **Monitoring Issues** | observability-engineer | Tracing, metrics, logging, alerting |
| **Code Quality** | spring-boot-quality-enforcer | Spring Boot standards, static analysis |
| **Testing Strategy** | microservices-testing-specialist | Unit, integration, contract testing |

## Best Practices for Agent Integration

### 1. **Service-First Design**

- Always consult `microservices-architect` before creating new services
- Use `database-per-service-specialist` for data modeling decisions
- Follow established service boundary patterns

### 2. **Maintain Consistency**

- Follow Spring Boot conventions from `spring-boot-developer`
- Use security patterns from `security-specialist`
- Apply containerization practices from `containerization-specialist`

### 3. **Validate Continuously**

- Use `microservices-testing-specialist` patterns for comprehensive testing
- Apply `spring-boot-quality-enforcer` standards for code quality
- Follow `api-documentation-specialist` guidelines for API docs

### 4. **Event-Driven Integration**

- Respect service boundaries and use events for cross-service communication
- Follow `event-driven-specialist` patterns for async messaging
- Use `api-gateway-specialist` for synchronous service integration

## Agent Specialization Areas

The Positivity POS system follows these specialized agent patterns:

- **Core Architecture**: `microservices-architect`, `spring-boot-developer`, `api-gateway-specialist`
- **Infrastructure**: `containerization-specialist`, `database-per-service-specialist`, `observability-engineer`
- **Security & Events**: `security-specialist`, `event-driven-specialist`
- **Quality & Documentation**: `microservices-testing-specialist`, `spring-boot-quality-enforcer`, `api-documentation-specialist`
- **Domain-Specific**: `automotive-domain-expert`, `angular-frontend-expert`, `spring-cloud-expert`

Each agent specialization includes:

- Role definition and microservices responsibilities
- Spring Boot and Spring Cloud expertise areas
- Integration patterns with other microservices agents
- Specific guidelines for POS system development
- Service boundary and communication patterns

## Implementation Notes

When working with microservices agents in Kiro:

1. **Service-Oriented Approach** - Always consider service boundaries and domain responsibilities
2. **Spring Boot Conventions** - Follow Spring Boot and Spring Cloud best practices consistently
3. **Security-First** - Integrate security considerations from the beginning of service design
4. **Event-Driven Design** - Prefer async communication between services when possible
5. **Container-Ready** - Ensure all services are designed for containerization and cloud deployment
6. **Observability Built-In** - Include monitoring, tracing, and logging from service inception
7. **Database Per Service** - Maintain data isolation and service autonomy
8. **API Gateway Centralization** - Route all external traffic through the API gateway
9. **Testing at All Levels** - Implement unit, integration, and contract testing strategies
10. **Documentation as Code** - Maintain API documentation alongside service implementation
