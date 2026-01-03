package com.pos.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;

/**
 * Unit tests for ArchitectureContext class.
 */
@DisplayName("ArchitectureContext")
class ArchitectureContextTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create ArchitectureContext using builder")
        void shouldCreateArchitectureContextUsingBuilder() {
            // When
            ArchitectureContext context = ArchitectureContext.builder().build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should set default agentDomain to 'architecture'")
        void shouldSetDefaultAgentDomain() {
            // When
            ArchitectureContext context = ArchitectureContext.builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("architecture");
        }

        @Test
        @DisplayName("should set default contextType to 'architecture-context'")
        void shouldSetDefaultContextType() {
            // When
            ArchitectureContext context = ArchitectureContext.builder().build();

            // Then
            assertThat(context.getContextType()).isEqualTo("architecture-context");
        }

        @Test
        @DisplayName("should add service")
        void shouldAddService() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .addService("User Service", "Team Alpha")
                    .build();

            // Then
            assertThat(context.getServices()).contains("User Service");
        }

        @Test
        @DisplayName("should add service owner")
        void shouldAddServiceOwner() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .serviceOwners(Map.of("User Service", "Team Alpha"))
                    .build();

            // Then
            assertThat(context.getServiceOwners()).containsEntry("User Service", "Team Alpha");
        }

        @Test
        @DisplayName("should add architecture pattern")
        void shouldAddPattern() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .addPattern("Microservices")
                    .build();

            // Then
            assertThat(context.getPatterns()).contains("Microservices");
        }

        @Test
        @DisplayName("should add architecture decision")
        void shouldAddDecision() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .addDecision("ADR-001"," Initial architecture decision")
                    .build();

            // Then
            assertThat(context.getDecisions()).contains("ADR-001");
        }

        @Test
        @DisplayName("should add decision record")
        void shouldAddDecisionRecord() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .decisionRecords(Map.of("ADR-001", "Use microservices architecture"))
                    .build();

            // Then
            assertThat(context.getDecisionRecords()).containsEntry("ADR-001", "Use microservices architecture");
        }

        @Test
        @DisplayName("should add constraint")
        void shouldAddConstraint() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .addConstraint("Must support 10000 concurrent users")
                    .build();

            // Then
            assertThat(context.getConstraints()).contains("Must support 10000 concurrent users");
        }

        @Test
        @DisplayName("should add integration")
        void shouldAddIntegration() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .addIntegration("Payment Gateway")
                    .build();

            // Then
            assertThat(context.getIntegrations()).contains("Payment Gateway");
        }

        @Test
        @DisplayName("should add interface")
        void shouldAddInterface() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .addInterface("REST API", "/api/v1/users")
                    .build();

            // Then
            assertThat(context.getInterfaces()).containsEntry("REST API", "/api/v1/users");
        }

        @Test
        @DisplayName("should add data flow")
        void shouldAddDataFlow() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .addDataFlow("User -> Order Service")
                    .build();

            // Then
            assertThat(context.getDataFlows()).contains("User -> Order Service");
        }

        @Test
        @DisplayName("should add quality attribute")
        void shouldAddQualityAttribute() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .addQualityAttribute("High Availability")
                    .build();

            // Then
            assertThat(context.getQualityAttributes()).contains("High Availability");
        }
    }

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("should return defensive copy of services")
        void shouldReturnDefensiveCopyOfServices() {
            // Given
            ArchitectureContext context = ArchitectureContext.builder()
                    .addService("Service 1", "Team Bravo")
                    .build();

            // When
            var services = context.getServices();
            services.add("Hacked Service");

            // Then
            assertThat(context.getServices()).doesNotContain("Hacked Service");
        }

        @Test
        @DisplayName("should return defensive copy of service owners")
        void shouldReturnDefensiveCopyOfServiceOwners() {
            // Given
            ArchitectureContext context = ArchitectureContext.builder()
                    .serviceOwners(Map.of("Service 1", "Owner 1"))
                    .build();

            // When
            var owners = context.getServiceOwners();
            owners.put("Hacked", "Evil");

            // Then
            assertThat(context.getServiceOwners()).doesNotContainKey("Hacked");
        }
    }

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should create comprehensive microservices architecture context")
        void shouldCreateComprehensiveMicroservicesContext() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .description("E-commerce Platform Architecture")
                    // Services
                    .addService("User Service"," Team Alpha")
                    .addService("Order Service","Team Beta")
                    .addService("Payment Service","Team Gamma")
                    .addService("Inventory Service","Team Delta")
                    .addService("Notification Service","Team Epsilon")
                    // Service Ownership
                    .serviceOwners(Map.of(
                        "User Service", "Team Alpha",
                        "Order Service", "Team Beta",
                        "Payment Service", "Team Gamma",
                        "Inventory Service", "Team Delta",
                        "Notification Service", "Team Epsilon"
                    ))
                    // Patterns
                    .addPattern("Microservices")
                    .addPattern("Event-Driven Architecture")
                    .addPattern("API Gateway")
                    .addPattern("Service Mesh")
                    .addPattern("CQRS")
                    // Architecture Decisions
                    .addDecision("ADR-001", "Initial architecture decision")
                    .addDecision("ADR-002", "Second architecture decision")
                    .decisionRecords(Map.of("ADR-001", "Use microservices for scalability","ADR-002", "Implement event-driven communication"))
                    // Constraints
                    .addConstraint("99.9% uptime SLA")
                    .addConstraint("Sub-second response time")
                    .addConstraint("Must support 100K concurrent users")
                    // Integrations
                    .addIntegration("Stripe Payment Gateway")
                    .addIntegration("SendGrid Email Service")
                    .addIntegration("Twilio SMS Service")
                    // Interfaces
                    .addInterface("REST API", "https://api.example.com/v1")
                    .addInterface("GraphQL API", "https://api.example.com/graphql")
                    // Data Flows
                    .addDataFlow("API Gateway -> User Service")
                    .addDataFlow("Order Service -> Payment Service")
                    .addDataFlow("Order Service -> Inventory Service")
                    // Quality Attributes
                    .addQualityAttribute("Scalability")
                    .addQualityAttribute("Availability")
                    .addQualityAttribute("Performance")
                    .addQualityAttribute("Security")
                    .addQualityAttribute("Maintainability")
                    .build();

            // Then
            assertThat(context.getServices()).hasSize(5);
            assertThat(context.getServiceOwners()).hasSize(5);
            assertThat(context.getPatterns()).hasSize(5);
            assertThat(context.getDecisions()).hasSize(2);
            assertThat(context.getDecisionRecords()).hasSize(2);
            assertThat(context.getConstraints()).hasSize(3);
            assertThat(context.getIntegrations()).hasSize(3);
            assertThat(context.getInterfaces()).hasSize(2);
            assertThat(context.getDataFlows()).hasSize(3);
            assertThat(context.getQualityAttributes()).hasSize(5);
        }

        @Test
        @DisplayName("should create architecture context for monolithic application")
        void shouldCreateMonolithicArchitectureContext() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .description("Legacy Monolithic Application")
                    .addService("Main Application", "Platform Team")
                    .serviceOwners(Map.of("Main Application", "Platform Team"))
                    .addPattern("Monolithic")
                    .addPattern("Layered Architecture")
                    .addDecision("ADR-101", "Maintain monolith for simplicity")
                    .decisionRecords(Map.of("ADR-101", "Maintain monolith for simplicity"))
                    .addConstraint("Single deployment unit")
                    .addQualityAttribute("Simplicity")
                    .addQualityAttribute("Consistency")
                    .build();

            // Then
            assertThat(context.getServices()).hasSize(1);
            assertThat(context.getPatterns()).contains("Monolithic", "Layered Architecture");
        }

        @Test
        @DisplayName("should create architecture context for serverless application")
        void shouldCreateServerlessArchitectureContext() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .description("Serverless Image Processing Pipeline")
                    .addService("Image Upload Function", "Team Omega")
                    .addService("Image Processing Function", "Team Omega")
                    .addService("Thumbnail Generator Function", "Team Omega")
                    .addService("Metadata Extractor Function", "Team Omega")
                    .addPattern("Serverless")
                    .addPattern("Event-Driven")
                    .addPattern("Function as a Service")
                    .addConstraint("Cost optimization priority")
                    .addConstraint("Auto-scaling required")
                    .addIntegration("AWS S3")
                    .addIntegration("AWS Lambda")
                    .addIntegration("AWS DynamoDB")
                    .addDataFlow("S3 Upload -> Lambda Trigger")
                    .addDataFlow("Lambda -> DynamoDB")
                    .addQualityAttribute("Cost Efficiency")
                    .addQualityAttribute("Scalability")
                    .addQualityAttribute("Elasticity")
                    .build();

            // Then
            assertThat(context.getServices()).hasSize(4);
            assertThat(context.getPatterns()).contains("Serverless", "Event-Driven", "Function as a Service");
            assertThat(context.getIntegrations()).hasSize(3);
        }

        @Test
        @DisplayName("should create architecture context with ADR tracking")
        void shouldCreateArchitectureContextWithADRTracking() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .description("Architecture Decision Records")
                    .addDecision("ADR-001", "Use PostgreSQL for relational data")
                    .addDecision("ADR-002", "Use Redis for caching layer")
                    .addDecision("ADR-003", "Use Kafka for event streaming")
                    .decisionRecords(Map.of("ADR-001", "Use PostgreSQL for relational data", "ADR-002", "Use Redis for caching layer", "ADR-003", "Use Kafka for event streaming"))
                    .build();

            // Then
            assertThat(context.getDecisions()).hasSize(3);
            assertThat(context.getDecisionRecords()).hasSize(3);
            assertThat(context.getDecisionRecords())
                    .containsEntry("ADR-001", "Use PostgreSQL for relational data")
                    .containsEntry("ADR-002", "Use Redis for caching layer")
                    .containsEntry("ADR-003", "Use Kafka for event streaming");
        }

        @Test
        @DisplayName("should create architecture context with quality attributes focus")
        void shouldCreateArchitectureContextWithQualityAttributesFocus() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .description("Quality Attributes Analysis")
                    .addQualityAttribute("Performance: < 200ms response time")
                    .addQualityAttribute("Availability: 99.99% uptime")
                    .addQualityAttribute("Scalability: Support 1M users")
                    .addQualityAttribute("Security: SOC 2 compliance")
                    .addQualityAttribute("Maintainability: < 2 weeks for features")
                    .addQualityAttribute("Testability: 80% code coverage")
                    .addQualityAttribute("Observability: Full distributed tracing")
                    .addConstraint("Budget: $50K/month infrastructure")
                    .addConstraint("Team size: 15 engineers")
                    .build();

            // Then
            assertThat(context.getQualityAttributes()).hasSize(7);
            assertThat(context.getConstraints()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("should handle empty architecture context")
        void shouldHandleEmptyArchitectureContext() {
            // When
            ArchitectureContext context = ArchitectureContext.builder().build();

            // Then
            assertThat(context.getServices()).isEmpty();
            assertThat(context.getServiceOwners()).isEmpty();
            assertThat(context.getPatterns()).isEmpty();
            assertThat(context.getDecisions()).isEmpty();
            assertThat(context.getConstraints()).isEmpty();
            assertThat(context.getIntegrations()).isEmpty();
        }

        @Test
        @DisplayName("should handle service without owner")
        void shouldHandleServiceWithoutOwner() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .addService("Orphan Service", null)
                    .build();

            // Then
            assertThat(context.getServices()).contains("Orphan Service");
            assertThat(context.getServiceOwners()).isEmpty();
        }

        @Test
        @DisplayName("should handle decision without record")
        void shouldHandleDecisionWithoutRecord() {
            // When
            ArchitectureContext context = ArchitectureContext.builder()
                    .addDecision("ADR-999", null)
                    .build();

            // Then
            assertThat(context.getDecisions()).contains("ADR-999");
            assertThat(context.getDecisionRecords()).isEmpty();
        }
    }
}
