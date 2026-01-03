package com.pos.agent.context;

import static com.pos.agent.context.TestingContext.TestSuiteStatus.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for TestingContext class.
 */
@DisplayName("TestingContext")
class TestingContextTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create TestingContext using builder")
        void shouldCreateTestingContextUsingBuilder() {
            // When
            TestingContext context = TestingContext.builder().build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should set default agentDomain to 'testing'")
        void shouldSetDefaultAgentDomain() {
            // When
            TestingContext context = TestingContext.builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("testing");
        }

        @Test
        @DisplayName("should set default contextType to 'testing-context'")
        void shouldSetDefaultContextType() {
            // When
            TestingContext context = TestingContext.builder().build();

            // Then
            assertThat(context.getContextType()).isEqualTo("testing-context");
        }

        @Test
        @DisplayName("should add test suite")
        void shouldAddTestSuite() {
            // When
            TestingContext context = TestingContext.builder()
                    .addTestSuite("Unit Tests", PASSED)
                    .build();

            // Then
            assertThat(context.getTestSuites()).contains("Unit Tests");
        }

        @Test
        @DisplayName("should add multiple test suites")
        void shouldAddMultipleTestSuites() {
            // When
            TestingContext context = TestingContext.builder()
                    .addTestSuite("Unit Tests", PASSED)
                    .addTestSuite("Integration Tests", PASSED)
                    .addTestSuite("E2E Tests", PASSED)
                    .build();

            // Then
            assertThat(context.getTestSuites())
                    .hasSize(3)
                    .contains("Unit Tests", "Integration Tests", "E2E Tests");
        }

        @Test
        @DisplayName("should add suite status")
        void shouldAddSuiteStatus() {
            // When
            TestingContext context = TestingContext.builder()
                    .addTestSuite("Unit Tests", PASSED)
                    .build();

            // Then
            assertThat(context.getSuiteStatuses()).containsEntry("Unit Tests", PASSED);
        }

        @Test
        @DisplayName("should add testing framework")
        void shouldAddTestingFramework() {
            // When
            TestingContext context = TestingContext.builder()
                    .addFramework("JUnit 5")
                    .build();

            // Then
            assertThat(context.getFrameworks()).contains("JUnit 5");
        }

        @Test
        @DisplayName("should add test environment")
        void shouldAddTestEnvironment() {
            // When
            TestingContext context = TestingContext.builder()
                    .addEnvironment("Staging")
                    .build();

            // Then
            assertThat(context.getEnvironments()).contains("Staging");
        }

        @Test
        @DisplayName("should add coverage metric")
        void shouldAddCoverageMetric() {
            // When
            TestingContext context = TestingContext.builder()
                    .addCoverageMetric("Line Coverage", 85.5)
                    .build();

            // Then
            assertThat(context.getCoverageMetrics()).containsEntry("Line Coverage", 85.5);
        }

        @Test
        @DisplayName("should add defect")
        void shouldAddDefect() {
            // When
            TestingContext context = TestingContext.builder()
                    .addDefect("BUG-123")
                    .build();

            // Then
            assertThat(context.getDefects()).contains("BUG-123");
        }
    }

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("should return defensive copy of test suites")
        void shouldReturnDefensiveCopyOfTestSuites() {
            // Given
            TestingContext context = TestingContext.builder()
                    .addTestSuite("Suite 1", PASSED)
                    .build();

            // When
            var suites = context.getTestSuites();
            suites.add("Hacked Suite");

            // Then
            assertThat(context.getTestSuites()).doesNotContain("Hacked Suite");
        }

        @Test
        @DisplayName("should return defensive copy of suite statuses")
        void shouldReturnDefensiveCopyOfSuiteStatuses() {
            // Given
            TestingContext context = TestingContext.builder()
                    .addTestSuite("Suite 1", PASSED)
                    .build();

            // When
            var statuses = context.getSuiteStatuses();
            statuses.put("Hacked", FAILED);

            // Then
            assertThat(context.getSuiteStatuses()).doesNotContainKey("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of coverage metrics")
        void shouldReturnDefensiveCopyOfCoverageMetrics() {
            // Given
            TestingContext context = TestingContext.builder()
                    .addCoverageMetric("Coverage", 80.0)
                    .build();

            // When
            var metrics = context.getCoverageMetrics();
            metrics.put("Hacked", 100.0);

            // Then
            assertThat(context.getCoverageMetrics()).doesNotContainKey("Hacked");
        }
    }

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should create comprehensive testing context")
        void shouldCreateComprehensiveTestingContext() {
            // When
            TestingContext context = TestingContext.builder()
                    .description("Q1 2026 Testing Strategy")
                    .addTestSuite("Unit Tests", PASSED)
                    .addTestSuite("Integration Tests", PASSED)
                    .addTestSuite("E2E Tests", IN_PROGRESS)
                    .addTestSuite("Performance Tests", NOT_STARTED)
                    .addFramework("JUnit 5")
                    .addFramework("Mockito")
                    .addFramework("Selenium")
                    .addFramework("JMeter")
                    .addEnvironment("Development")
                    .addEnvironment("Staging")
                    .addEnvironment("Production")
                    .addCoverageMetric("Line Coverage", 87.3)
                    .addCoverageMetric("Branch Coverage", 75.2)
                    .addCoverageMetric("Method Coverage", 92.1)
                    .addDefect("BUG-101")
                    .addDefect("BUG-102")
                    .build();

            // Then
            assertThat(context.getTestSuites()).hasSize(4);
            assertThat(context.getSuiteStatuses()).hasSize(4);
            assertThat(context.getFrameworks()).hasSize(4);
            assertThat(context.getEnvironments()).hasSize(3);
            assertThat(context.getCoverageMetrics()).hasSize(3);
            assertThat(context.getDefects()).hasSize(2);
        }

        @Test
        @DisplayName("should create testing context for CI/CD pipeline")
        void shouldCreateTestingContextForCICD() {
            // When
            TestingContext context = TestingContext.builder()
                    .description("CI/CD Automated Tests")
                    .addTestSuite("Smoke Tests", PASSED)
                    .addTestSuite("Regression Tests", PASSED)
                    .addFramework("JUnit 5")
                    .addFramework("TestNG")
                    .addEnvironment("CI")
                    .addCoverageMetric("Code Coverage", 80.0)
                    .build();

            // Then
            assertThat(context.getTestSuites()).containsExactlyInAnyOrder("Smoke Tests", "Regression Tests");
            assertThat(context.getSuiteStatuses().values()).containsOnly(PASSED);
        }

        @Test
        @DisplayName("should create testing context with defects")
        void shouldCreateTestingContextWithDefects() {
            // When
            TestingContext context = TestingContext.builder()
                    .description("Bug Tracking Context")
                    .addTestSuite("Regression Tests", FAILED)
                    .addDefect("BUG-201: Login fails with special characters")
                    .addDefect("BUG-202: Payment gateway timeout")
                    .addDefect("BUG-203: Incorrect tax calculation")
                    .addEnvironment("QA")
                    .build();

            // Then
            assertThat(context.getDefects()).hasSize(3);
            assertThat(context.getSuiteStatuses()).containsValue(FAILED);
        }

        @Test
        @DisplayName("should create testing context for performance testing")
        void shouldCreateTestingContextForPerformanceTesting() {
            // When
            TestingContext context = TestingContext.builder()
                    .description("Load and Performance Testing")
                    .addTestSuite("Load Tests", IN_PROGRESS)
                    .addTestSuite("Stress Tests", IN_PROGRESS)
                    .addTestSuite("Endurance Tests", IN_PROGRESS)
                    .addFramework("JMeter")
                    .addFramework("Gatling")
                    .addEnvironment("Performance")
                    .addCoverageMetric("Response Time P95", 250.0)
                    .addCoverageMetric("Throughput RPS", 1000.0)
                    .build();

            // Then
            assertThat(context.getTestSuites()).contains("Load Tests", "Stress Tests", "Endurance Tests");
            assertThat(context.getCoverageMetrics()).containsKeys("Response Time P95", "Throughput RPS");
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("should handle empty testing context")
        void shouldHandleEmptyTestingContext() {
            // When
            TestingContext context = TestingContext.builder().build();

            // Then
            assertThat(context.getTestSuites()).isEmpty();
            assertThat(context.getSuiteStatuses()).isEmpty();
            assertThat(context.getFrameworks()).isEmpty();
            assertThat(context.getEnvironments()).isEmpty();
            assertThat(context.getCoverageMetrics()).isEmpty();
            assertThat(context.getDefects()).isEmpty();
        }

        @Test
        @DisplayName("should handle zero coverage metrics")
        void shouldHandleZeroCoverageMetrics() {
            // When
            TestingContext context = TestingContext.builder()
                    .addCoverageMetric("Coverage", 0.0)
                    .build();

            // Then
            assertThat(context.getCoverageMetrics()).containsEntry("Coverage", 0.0);
        }

        @Test
        @DisplayName("should handle 100 percent coverage")
        void shouldHandle100PercentCoverage() {
            // When
            TestingContext context = TestingContext.builder()
                    .addCoverageMetric("Line Coverage", 100.0)
                    .build();

            // Then
            assertThat(context.getCoverageMetrics()).containsEntry("Line Coverage", 100.0);
        }
    }
}
