package com.pos.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for BusinessContext class.
 */
@DisplayName("BusinessContext")
class BusinessContextTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create BusinessContext using builder")
        void shouldCreateBusinessContextUsingBuilder() {
            // When
            BusinessContext context = BusinessContext.builder().build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should set default agentDomain to 'business'")
        void shouldSetDefaultAgentDomain() {
            // When
            BusinessContext context = BusinessContext.builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("business");
        }

        @Test
        @DisplayName("should set default contextType to 'business-context'")
        void shouldSetDefaultContextType() {
            // When
            BusinessContext context = BusinessContext.builder().build();

            // Then
            assertThat(context.getContextType()).isEqualTo("business-context");
        }

        @Test
        @DisplayName("should add business goal")
        void shouldAddBusinessGoal() {
            // When
            BusinessContext context = BusinessContext.builder()
                    .addGoal("Increase revenue")
                    .build();

            // Then
            assertThat(context.getBusinessGoals()).contains("Increase revenue");
        }

        @Test
        @DisplayName("should add multiple business goals")
        void shouldAddMultipleBusinessGoals() {
            // When
            BusinessContext context = BusinessContext.builder()
                    .addGoal("Improve customer satisfaction")
                    .addGoal("Reduce operational costs")
                    .addGoal("Expand market share")
                    .build();

            // Then
            assertThat(context.getBusinessGoals())
                    .hasSize(3)
                    .contains("Improve customer satisfaction", "Reduce operational costs", "Expand market share");
        }

        @Test
        @DisplayName("should add stakeholder")
        void shouldAddStakeholder() {
            // When
            BusinessContext context = BusinessContext.builder()
                    .addStakeholder("Product Owner", "John Doe")
                    .build();

            // Then
            assertThat(context.getStakeholders()).containsEntry("Product Owner", "John Doe");
        }

        @Test
        @DisplayName("should add multiple stakeholders")
        void shouldAddMultipleStakeholders() {
            // When
            BusinessContext context = BusinessContext.builder()
                    .addStakeholder("CEO", "Jane Smith")
                    .addStakeholder("CTO", "Bob Johnson")
                    .addStakeholder("CFO", "Alice Brown")
                    .build();

            // Then
            assertThat(context.getStakeholders())
                    .hasSize(3)
                    .containsEntry("CEO", "Jane Smith")
                    .containsEntry("CTO", "Bob Johnson")
                    .containsEntry("CFO", "Alice Brown");
        }

        @Test
        @DisplayName("should add KPI")
        void shouldAddKPI() {
            // When
            BusinessContext context = BusinessContext.builder()
                    .addKpi("Revenue Growth", 15.5)
                    .build();

            // Then
            assertThat(context.getKpis()).containsEntry("Revenue Growth", 15.5);
        }

        @Test
        @DisplayName("should add multiple KPIs")
        void shouldAddMultipleKPIs() {
            // When
            BusinessContext context = BusinessContext.builder()
                    .addKpi("Customer Satisfaction", 4.5)
                    .addKpi("NPS Score", 75.0)
                    .addKpi("Churn Rate", 2.3)
                    .build();

            // Then
            assertThat(context.getKpis())
                    .hasSize(3)
                    .containsEntry("Customer Satisfaction", 4.5)
                    .containsEntry("NPS Score", 75.0)
                    .containsEntry("Churn Rate", 2.3);
        }

        @Test
        @DisplayName("should add product")
        void shouldAddProduct() {
            // When
            BusinessContext context = BusinessContext.builder()
                    .addProduct("Mobile App")
                    .build();

            // Then
            assertThat(context.getProducts()).contains("Mobile App");
        }

        @Test
        @DisplayName("should add process")
        void shouldAddProcess() {
            // When
            BusinessContext context = BusinessContext.builder()
                    .addProcess("Order Fulfillment", "Operations Team")
                    .build();

            // Then
            assertThat(context.getProcesses()).containsEntry("Order Fulfillment", "Operations Team");
        }

        @Test
        @DisplayName("should add regulation")
        void shouldAddRegulation() {
            // When
            BusinessContext context = BusinessContext.builder()
                    .addRegulation("GDPR")
                    .build();

            // Then
            assertThat(context.getRegulations()).contains("GDPR");
        }
    }

    @Nested
    @DisplayName("Mutator Tests")
    class MutatorTests {

        @Test
        @DisplayName("should add goal after context creation")
        void shouldAddGoalAfterContextCreation() {
            // Given
            BusinessContext context = BusinessContext.builder().build();
            Instant originalTimestamp = context.getLastUpdated();

            // Wait a bit to ensure timestamp changes
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // When
            context.addGoal("New Goal");

            // Then
            assertThat(context.getBusinessGoals()).contains("New Goal");
            assertThat(context.getLastUpdated()).isAfter(originalTimestamp);
        }

        @Test
        @DisplayName("should not update timestamp when adding duplicate goal")
        void shouldNotUpdateTimestampWhenAddingDuplicateGoal() {
            // Given
            BusinessContext context = BusinessContext.builder()
                    .addGoal("Goal 1")
                    .build();
            Instant originalTimestamp = context.getLastUpdated();

            // Wait a bit
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // When
            context.addGoal("Goal 1");

            // Then
            assertThat(context.getLastUpdated()).isEqualTo(originalTimestamp);
        }

        @Test
        @DisplayName("should add stakeholder after context creation")
        void shouldAddStakeholderAfterContextCreation() {
            // Given
            BusinessContext context = BusinessContext.builder().build();

            // When
            context.addStakeholder("PM", "Sarah Lee");

            // Then
            assertThat(context.getStakeholders()).containsEntry("PM", "Sarah Lee");
        }

        @Test
        @DisplayName("should add KPI after context creation")
        void shouldAddKPIAfterContextCreation() {
            // Given
            BusinessContext context = BusinessContext.builder().build();

            // When
            context.addKpi("Conversion Rate", 3.5);

            // Then
            assertThat(context.getKpis()).containsEntry("Conversion Rate", 3.5);
        }

        @Test
        @DisplayName("should add product after context creation")
        void shouldAddProductAfterContextCreation() {
            // Given
            BusinessContext context = BusinessContext.builder().build();

            // When
            context.addProduct("Web Platform");

            // Then
            assertThat(context.getProducts()).contains("Web Platform");
        }

        @Test
        @DisplayName("should add process after context creation")
        void shouldAddProcessAfterContextCreation() {
            // Given
            BusinessContext context = BusinessContext.builder().build();

            // When
            context.addProcess("Inventory Management", "Warehouse Team");

            // Then
            assertThat(context.getProcesses()).containsEntry("Inventory Management", "Warehouse Team");
        }

        @Test
        @DisplayName("should add regulation after context creation")
        void shouldAddRegulationAfterContextCreation() {
            // Given
            BusinessContext context = BusinessContext.builder().build();

            // When
            context.addRegulation("HIPAA");

            // Then
            assertThat(context.getRegulations()).contains("HIPAA");
        }

        @Test
        @DisplayName("should not add null goal")
        void shouldNotAddNullGoal() {
            // Given
            BusinessContext context = BusinessContext.builder().build();

            // When
            context.addGoal(null);

            // Then
            assertThat(context.getBusinessGoals()).isEmpty();
        }

        @Test
        @DisplayName("should not add stakeholder with null role or owner")
        void shouldNotAddStakeholderWithNullValues() {
            // Given
            BusinessContext context = BusinessContext.builder().build();

            // When
            context.addStakeholder(null, "Owner");
            context.addStakeholder("Role", null);

            // Then
            assertThat(context.getStakeholders()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("should validate context with goals")
        void shouldValidateContextWithGoals() {
            // Given
            BusinessContext context = BusinessContext.builder()
                    .addGoal("Goal 1")
                    .build();

            // When/Then
            assertThat(context.isValid()).isTrue();
        }

        @Test
        @DisplayName("should validate context with products")
        void shouldValidateContextWithProducts() {
            // Given
            BusinessContext context = BusinessContext.builder()
                    .addProduct("Product 1")
                    .build();

            // When/Then
            assertThat(context.isValid()).isTrue();
        }

        @Test
        @DisplayName("should not validate empty context")
        void shouldNotValidateEmptyContext() {
            // Given
            BusinessContext context = BusinessContext.builder().build();

            // When/Then
            assertThat(context.isValid()).isFalse();
        }

        @Test
        @DisplayName("should check goal alignment")
        void shouldCheckGoalAlignment() {
            // Given
            BusinessContext context = BusinessContext.builder()
                    .addGoal("Increase Revenue")
                    .build();

            // When/Then
            assertThat(context.isAlignedWithGoal("Increase Revenue")).isTrue();
            assertThat(context.isAlignedWithGoal("increase revenue")).isTrue(); // case insensitive
            assertThat(context.isAlignedWithGoal("Other Goal")).isFalse();
        }

        @Test
        @DisplayName("should handle null goal in alignment check")
        void shouldHandleNullGoalInAlignmentCheck() {
            // Given
            BusinessContext context = BusinessContext.builder()
                    .addGoal("Goal 1")
                    .build();

            // When/Then
            assertThat(context.isAlignedWithGoal(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("should return defensive copy of business goals")
        void shouldReturnDefensiveCopyOfBusinessGoals() {
            // Given
            BusinessContext context = BusinessContext.builder()
                    .addGoal("Goal 1")
                    .build();

            // When
            var goals = context.getBusinessGoals();
            goals.add("Hacked Goal");

            // Then
            assertThat(context.getBusinessGoals()).doesNotContain("Hacked Goal");
        }

        @Test
        @DisplayName("should return defensive copy of stakeholders")
        void shouldReturnDefensiveCopyOfStakeholders() {
            // Given
            BusinessContext context = BusinessContext.builder()
                    .addStakeholder("CEO", "John")
                    .build();

            // When
            var stakeholders = context.getStakeholders();
            stakeholders.put("Hacker", "Evil");

            // Then
            assertThat(context.getStakeholders()).doesNotContainKey("Hacker");
        }

        @Test
        @DisplayName("should return defensive copy of KPIs")
        void shouldReturnDefensiveCopyOfKPIs() {
            // Given
            BusinessContext context = BusinessContext.builder()
                    .addKpi("Metric", 1.0)
                    .build();

            // When
            var kpis = context.getKpis();
            kpis.put("Hacked", 99.9);

            // Then
            assertThat(context.getKpis()).doesNotContainKey("Hacked");
        }
    }

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should create comprehensive business context for enterprise")
        void shouldCreateComprehensiveBusinessContext() {
            // When
            BusinessContext context = BusinessContext.builder()
                    .description("Q1 2026 Strategic Objectives")
                    .addGoal("Achieve 20% revenue growth")
                    .addGoal("Launch in 3 new markets")
                    .addGoal("Improve customer retention by 15%")
                    .addStakeholder("CEO", "John Smith")
                    .addStakeholder("VP Sales", "Jane Doe")
                    .addStakeholder("VP Product", "Bob Wilson")
                    .addKpi("Revenue Growth", 20.0)
                    .addKpi("Market Expansion", 3.0)
                    .addKpi("Customer Retention", 15.0)
                    .addProduct("Enterprise Platform")
                    .addProduct("Mobile App")
                    .addProcess("Sales Pipeline", "Sales Team")
                    .addProcess("Customer Onboarding", "Success Team")
                    .addRegulation("SOC 2")
                    .addRegulation("GDPR")
                    .requiresAuditTrail(true)
                    .build();

            // Then
            assertThat(context.isValid()).isTrue();
            assertThat(context.getBusinessGoals()).hasSize(3);
            assertThat(context.getStakeholders()).hasSize(3);
            assertThat(context.getKpis()).hasSize(3);
            assertThat(context.getProducts()).hasSize(2);
            assertThat(context.getProcesses()).hasSize(2);
            assertThat(context.getRegulations()).hasSize(2);
        }

        @Test
        @DisplayName("should create minimal viable business context")
        void shouldCreateMinimalViableBusinessContext() {
            // When
            BusinessContext context = BusinessContext.builder()
                    .addGoal("Launch MVP")
                    .build();

            // Then
            assertThat(context.isValid()).isTrue();
            assertThat(context.getBusinessGoals()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("toString Tests")
    class ToStringTests {

        @Test
        @DisplayName("should generate meaningful toString output")
        void shouldGenerateMeaningfulToStringOutput() {
            // Given
            BusinessContext context = BusinessContext.builder()
                    .addGoal("Goal 1")
                    .addGoal("Goal 2")
                    .addProduct("Product 1")
                    .build();

            // When
            String result = context.toString();

            // Then
            assertThat(result)
                    .contains("BusinessContext")
                    .contains("goals=2")
                    .contains("products=1");
        }
    }
}
