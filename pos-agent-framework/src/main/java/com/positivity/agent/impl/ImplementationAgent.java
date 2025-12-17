package com.positivity.agent.impl;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.AgentPerformanceSpec;
import com.positivity.agent.BaseAgent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Implementation Agent - Core Spring Boot microservice development expertise
 * Specializes in Spring Boot patterns, business logic, and data access
 */
@Component
public class ImplementationAgent extends BaseAgent {

        public ImplementationAgent() {
                super(
                                "implementation-agent",
                                "Implementation Agent",
                                "implementation",
                                Set.of("spring-boot", "java", "business-logic", "data-access", "rest-api",
                                                "microservices"),
                                Set.of("architecture-agent"), // Depends on architectural guidance
                                AgentPerformanceSpec.defaultSpec());
        }

        @Override
        protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
                String guidance = generateImplementationGuidance(request);
                List<String> recommendations = generateImplementationRecommendations(request);

                return AgentGuidanceResponse.success(
                                request.requestId(),
                                getId(),
                                guidance,
                                0.94, // High confidence for implementation guidance
                                recommendations,
                                Duration.ofMillis(150));
        }

        private String generateImplementationGuidance(AgentConsultationRequest request) {
                String baseGuidance = "Implementation Guidance for " + request.domain() + ":\n\n";
                String query = request.query().toLowerCase();

                // Enhanced Spring Boot pattern provision (REQ-002.1) - Check first for Spring
                // Boot patterns
                if (query.contains("spring boot") || query.contains("microservice") ||
                                (query.contains("implement") && !query.contains("business logic")
                                                && !query.contains("data access"))
                                ||
                                (query.contains("service") && query.contains("spring")) ||
                                (query.contains("service") && query.contains("layer") && !query.contains("boundary"))) {
                        return baseGuidance +
                                        "SPRING BOOT MICROSERVICE IMPLEMENTATION:\n\n" +
                                        "Core Spring Boot Patterns:\n" +
                                        "- Use Spring Boot 3.x with appropriate starter dependencies\n" +
                                        "- Implement layered architecture: Controller → Service → Repository\n" +
                                        "- Use @Controller, @Service, @Repository annotations properly\n" +
                                        "- Configure Spring Security for authentication and authorization\n" +
                                        "- Implement proper exception handling with @ControllerAdvice\n" +
                                        "- Use Spring Data for data access abstraction\n" +
                                        "- Configure structured logging with Logback/SLF4J\n" +
                                        "- Implement health checks and metrics with Spring Boot Actuator\n\n" +
                                        "Microservice Architecture Patterns:\n" +
                                        "- Follow database-per-service pattern\n" +
                                        "- Implement service discovery and registration\n" +
                                        "- Use API Gateway for external communication\n" +
                                        "- Implement circuit breaker patterns for resilience\n" +
                                        "- Use event-driven communication between services\n" +
                                        "- Implement proper configuration management with Spring Cloud Config\n" +
                                        "- Add distributed tracing with Spring Cloud Sleuth";
                }

                // Enhanced service boundary validation (REQ-002.4) - Check for more specific
                // DDD matches
                if (query.contains("business logic") || query.contains("business rules")
                                || query.contains("service boundary")
                                || query.contains("domain service") || query.contains("ddd")
                                || query.contains("aggregate")
                                || query.contains("bounded context") ||
                                (query.contains("service")
                                                && (query.contains("boundary") || query.contains("domain")))) {
                        return baseGuidance +
                                        "BUSINESS LOGIC & SERVICE BOUNDARY IMPLEMENTATION:\n\n" +
                                        "Service Boundary Enforcement:\n" +
                                        "- Implement domain services following DDD patterns\n" +
                                        "- Use proper service boundaries and avoid cross-domain calls\n" +
                                        "- Enforce service ownership and domain isolation\n" +
                                        "- Implement anti-corruption layers for external integrations\n" +
                                        "- Use bounded contexts to define service boundaries\n\n" +
                                        "Service Layer Design:\n" +
                                        "- Implement validation at service layer boundaries\n" +
                                        "- Use events for loose coupling between services\n" +
                                        "- Implement proper error handling and logging\n" +
                                        "- Consider async processing for long-running operations\n" +
                                        "- Use dependency injection for testability\n" +
                                        "- Implement proper transaction boundaries\n\n" +
                                        "Domain-Driven Design Patterns:\n" +
                                        "- Define clear aggregate boundaries\n" +
                                        "- Implement domain services for complex business logic\n" +
                                        "- Use value objects for immutable data\n" +
                                        "- Implement repository patterns for aggregate persistence\n" +
                                        "- Use domain events for cross-aggregate communication";
                }

                if (query.contains("api") || query.contains("rest")) {
                        return baseGuidance +
                                        "REST API IMPLEMENTATION:\n\n" +
                                        "- Follow RESTful conventions for resource naming\n" +
                                        "- Use proper HTTP status codes and methods (GET, POST, PUT, DELETE)\n" +
                                        "- Implement request/response validation with Bean Validation (@Valid)\n" +
                                        "- Use DTOs for API contracts to decouple from domain models\n" +
                                        "- Implement proper error responses with consistent format\n" +
                                        "- Add API documentation with OpenAPI/Swagger\n" +
                                        "- Consider versioning strategy for API evolution\n" +
                                        "- Implement proper content negotiation and HATEOAS where appropriate";
                }

                if (query.contains("data") || query.contains("database") || query.contains("postgresql")
                                || query.contains("postgres") || query.contains("elasticache")) {
                        return baseGuidance +
                                        "DATA ACCESS IMPLEMENTATION:\n\n" +
                                        "PostgreSQL Patterns:\n" +
                                        "- Use Spring Data JPA for relational database patterns\n" +
                                        "- Implement proper transaction management with @Transactional\n" +
                                        "- Use repository pattern for data access abstraction\n" +
                                        "- Implement proper connection pooling with HikariCP\n" +
                                        "- Use database migrations with Flyway or Liquibase\n" +
                                        "- Optimize queries with proper indexing strategies\n\n" +
                                        "ElastiCache Patterns:\n" +
                                        "- Consider caching strategies with Spring Cache (@Cacheable)\n" +
                                        "- Implement cache-aside, write-through, or write-behind patterns\n" +
                                        "- Use Redis or Memcached for distributed caching\n" +
                                        "- Implement proper cache invalidation strategies\n\n" +
                                        "General Data Access:\n" +
                                        "- Implement proper error handling for data operations\n" +
                                        "- Use optimistic locking for concurrent access\n" +
                                        "- Implement proper data validation and constraints";
                }

                return baseGuidance +
                                "GENERAL IMPLEMENTATION BEST PRACTICES:\n\n" +
                                "Code Quality:\n" +
                                "- Follow SOLID principles and clean code practices\n" +
                                "- Implement proper unit and integration testing\n" +
                                "- Use dependency injection and inversion of control\n" +
                                "- Implement proper configuration management\n" +
                                "- Follow Spring Boot conventions and best practices\n\n" +
                                "Security & Performance:\n" +
                                "- Ensure proper security implementation\n" +
                                "- Use appropriate design patterns\n" +
                                "- Implement proper monitoring and observability\n" +
                                "- Consider performance implications of design decisions";
        }

        private List<String> generateImplementationRecommendations(AgentConsultationRequest request) {
                String query = request.query().toLowerCase();

                if (query.contains("spring boot") || query.contains("microservice")) {
                        return List.of(
                                        "Use Spring Boot 3.x with appropriate starter dependencies",
                                        "Implement layered architecture: Controller → Service → Repository",
                                        "Use @Controller, @Service, @Repository annotations properly",
                                        "Configure Spring Security for authentication",
                                        "Implement health checks with Spring Boot Actuator",
                                        "Use Spring Data for data access abstraction",
                                        "Follow microservice architecture patterns",
                                        "Implement service discovery and API Gateway integration",
                                        "Use event-driven communication between services",
                                        "Add distributed tracing and monitoring");
                }

                if (query.contains("business logic") || query.contains("service") || query.contains("boundary")) {
                        return List.of(
                                        "Implement domain services following DDD patterns",
                                        "Use proper service boundaries and avoid cross-domain calls",
                                        "Enforce service ownership and domain isolation",
                                        "Implement validation at service layer boundaries",
                                        "Use events for loose coupling between services",
                                        "Implement anti-corruption layers for external integrations",
                                        "Define clear aggregate boundaries",
                                        "Use bounded contexts to define service boundaries",
                                        "Implement proper transaction boundaries",
                                        "Follow domain-driven design principles");
                }

                if (query.contains("data") || query.contains("database")) {
                        return List.of(
                                        "Use Spring Data JPA for PostgreSQL databases",
                                        "Implement proper transaction management with @Transactional",
                                        "Use repository pattern for data access abstraction",
                                        "Consider caching strategies with Spring Cache (ElastiCache)",
                                        "Implement proper connection pooling and configuration",
                                        "Use database migrations with Flyway or Liquibase",
                                        "Implement proper error handling for data operations",
                                        "Use optimistic locking for concurrent access",
                                        "Implement proper data validation and constraints",
                                        "Follow database-per-service pattern");
                }

                return List.of(
                                "Use Spring Boot 3.x with appropriate starters",
                                "Implement layered architecture pattern",
                                "Follow REST API best practices",
                                "Use proper data access patterns",
                                "Implement comprehensive error handling",
                                "Add proper logging and monitoring",
                                "Ensure security best practices",
                                "Write comprehensive tests",
                                "Use dependency injection properly",
                                "Follow Spring Boot conventions and service boundary validation");
        }
}