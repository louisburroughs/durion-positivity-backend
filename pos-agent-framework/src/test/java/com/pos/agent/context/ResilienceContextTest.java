package com.pos.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ResilienceContext class.
 * Tests resilience and fault tolerance aspects including:
 * - Circuit breakers and retry patterns
 * - Backoff strategies and bulkhead patterns
 * - Thread pool configurations
 * - Chaos engineering experiments
 * - SLI/SLO definitions
 */
@DisplayName("ResilienceContext")
class ResilienceContextTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create ResilienceContext using builder")
        void shouldCreateResilienceContextUsingBuilder() {
            // When
            ResilienceContext context = ResilienceContext.builder().build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should set default agentDomain to 'resilience'")
        void shouldSetDefaultAgentDomain() {
            // When
            ResilienceContext context = ResilienceContext.builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("resilience");
        }

        @Test
        @DisplayName("should set default contextType to 'resilience-context'")
        void shouldSetDefaultContextType() {
            // When
            ResilienceContext context = ResilienceContext.builder().build();

            // Then
            assertThat(context.getContextType()).isEqualTo("resilience-context");
        }

        @Test
        @DisplayName("should set service name")
        void shouldSetServiceName() {
            // When
            ResilienceContext context = ResilienceContext.builder()
                    .serviceName("payment-service")
                    .build();

            // Then
            assertThat(context.getServiceName()).isEqualTo("payment-service");
        }

        @Test
        @DisplayName("should set platform")
        void shouldSetPlatform() {
            // When
            ResilienceContext context = ResilienceContext.builder()
                    .platform("Kubernetes")
                    .build();

            // Then
            assertThat(context.getPlatform()).isEqualTo("Kubernetes");
        }

        @Test
        @DisplayName("should add circuit breaker with configuration")
        void shouldAddCircuitBreaker() {
            // When
            ResilienceContext context = ResilienceContext.builder()
                    .addCircuitBreaker("PaymentGateway", java.util.Map.of("status", "open"))
                    .build();

            // Then
            assertThat(context.getCircuitBreakers()).contains("PaymentGateway");
        }

        @Test
        @DisplayName("should add retry pattern with configuration")
        void shouldAddRetryPattern() {
            // When
            ResilienceContext context = ResilienceContext.builder()
                    .addRetryPattern("DatabaseRead", java.util.Map.of("type", "exponential"))
                    .build();

            // Then
            assertThat(context.getRetryPatterns()).contains("DatabaseRead");
        }

        @Test
        @DisplayName("should add backoff strategy")
        void shouldAddBackoffStrategy() {
            // When
            ResilienceContext context = ResilienceContext.builder()
                    .addBackoffStrategy("Exponential")
                    .build();

            // Then
            assertThat(context.getBackoffStrategies()).contains("Exponential");
        }

        @Test
        @DisplayName("should add bulkhead pattern")
        void shouldAddBulkheadPattern() {
            // When
            ResilienceContext context = ResilienceContext.builder()
                    .addBulkheadPattern("UserService")
                    .build();

            // Then
            assertThat(context.getBulkheadPatterns()).contains("UserService");
        }

        @Test
        @DisplayName("should add thread pool configuration")
        void shouldAddThreadPoolConfig() {
            // When
            ResilienceContext context = ResilienceContext.builder()
                    .addThreadPool("UserService")
                    .build();

            // Then
            assertThat(context.getThreadPools()).contains("UserService");
        }

        @Test
        @DisplayName("should add chaos experiment")
        void shouldAddChaosExperiment() {
            // When
            ResilienceContext context = ResilienceContext.builder()
                    .addChaosExperiment("Network Latency")
                    .build();

            // Then
            assertThat(context.getChaosExperiments()).contains("Network Latency");
        }

        @Test
        @DisplayName("should add health check")
        void shouldAddHealthCheck() {
            // When
            ResilienceContext context = ResilienceContext.builder()
                    .addHealthCheck("ServiceAvailability")
                    .build();

            // Then
            assertThat(context.getHealthChecks()).contains("ServiceAvailability");
        }

        @Test
        @DisplayName("should add SLI/SLO definition")
        void shouldAddSLISLODefinition() {
            // When
            ResilienceContext context = ResilienceContext.builder()
                    .addSliSloDefinition("Availability", java.util.Map.of("target", "99.9%"))
                    .build();

            // Then
            assertThat(context.getSliSloDefinitions()).containsKey("Availability");
        }
    }

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("should return defensive copy of circuit breakers")
        void shouldReturnDefensiveCopyOfCircuitBreakers() {
            // Given
            ResilienceContext context = ResilienceContext.builder()
                    .addCircuitBreaker("CB1", java.util.Map.of("status", "open"))
                    .build();

            // When
            var breakers = context.getCircuitBreakers();
            breakers.add("Hacked");

            // Then
            assertThat(context.getCircuitBreakers()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of retry patterns")
        void shouldReturnDefensiveCopyOfRetryPatterns() {
            // Given
            ResilienceContext context = ResilienceContext.builder()
                    .addRetryPattern("Retry1", java.util.Map.of("type", "exponential"))
                    .build();

            // When
            var patterns = context.getRetryPatterns();
            patterns.add("Hacked");

            // Then
            assertThat(context.getRetryPatterns()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of chaos experiments")
        void shouldReturnDefensiveCopyOfChaosExperiments() {
            // Given
            ResilienceContext context = ResilienceContext.builder()
                    .addChaosExperiment("Exp1")
                    .build();

            // When
            var experiments = context.getChaosExperiments();
            experiments.add("Hacked");

            // Then
            assertThat(context.getChaosExperiments()).doesNotContain("Hacked");
        }
    }

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should create comprehensive resilience context")
        void shouldCreateComprehensiveResilienceContext() {
            // When
            ResilienceContext context = ResilienceContext.builder()
                    .serviceName("order-service")
                    .platform("Kubernetes")
                    .description("Order Service Resilience Strategy")
                    .addCircuitBreaker("PaymentGateway", java.util.Map.of("status", "closed"))
                    .addCircuitBreaker("InventoryService", java.util.Map.of("status", "closed"))
                    .addRetryPattern("DatabaseRead", java.util.Map.of("type", "exponential"))
                    .addRetryPattern("ExternalAPI", java.util.Map.of("type", "linear"))
                    .addBackoffStrategy("Exponential")
                    .addBackoffStrategy("Linear")
                    .addBulkheadPattern("PaymentService")
                    .addBulkheadPattern("NotificationService")
                    .addThreadPool("PaymentService")
                    .addChaosExperiment("Network Latency")
                    .addChaosExperiment("Service Failure")
                    .addHealthCheck("ServiceAvailability")
                    .addHealthCheck("DependencyHealth")
                    .addSliSloDefinition("Availability", java.util.Map.of("target", "99.9%"))
                    .addSliSloDefinition("Latency", java.util.Map.of("target", "200ms"))
                    .build();

            // Then
            assertThat(context.getCircuitBreakers()).hasSize(2);
            assertThat(context.getRetryPatterns()).hasSize(2);
            assertThat(context.getBackoffStrategies()).hasSize(2);
            assertThat(context.getBulkheadPatterns()).hasSize(2);
            assertThat(context.getThreadPools()).hasSize(1);
            assertThat(context.getChaosExperiments()).hasSize(2);
            assertThat(context.getHealthChecks()).hasSize(2);
            assertThat(context.getSliSloDefinitions()).hasSize(2);
        }

        @Test
        @DisplayName("should create context with multi-level circuit breaker strategy")
        void shouldCreateMultiLevelCircuitBreakerStrategy() {
            // When
            ResilienceContext context = ResilienceContext.builder()
                    .serviceName("api-gateway")
                    .addCircuitBreaker("UserService", java.util.Map.of("status", "closed"))
                    .addCircuitBreaker("OrderService", java.util.Map.of("status", "closed"))
                    .addCircuitBreaker("PaymentService", java.util.Map.of("status", "closed"))
                    .build();

            // Then
            assertThat(context.getCircuitBreakers()).hasSize(3);
        }

        @Test
        @DisplayName("should create context with comprehensive SLI/SLO targets")
        void shouldCreateComprehensiveSLISLOContext() {
            // When
            ResilienceContext context = ResilienceContext.builder()
                    .serviceName("critical-service")
                    .platform("Production")
                    .addSliSloDefinition("Availability", java.util.Map.of("target", "99.99%", "window", "30days"))
                    .addSliSloDefinition("Latency P50", java.util.Map.of("target", "100ms", "window", "30days"))
                    .addSliSloDefinition("Latency P99", java.util.Map.of("target", "500ms", "window", "30days"))
                    .addSliSloDefinition("Error Rate", java.util.Map.of("target", "0.1%", "window", "30days"))
                    .addHealthCheck("ServiceHealth")
                    .addHealthCheck("DependencyHealth")
                    .build();

            // Then
            assertThat(context.getSliSloDefinitions()).hasSize(4);
        }

        @Test
        @DisplayName("should create context with chaos engineering experiments")
        void shouldCreateChaosEngineeringContext() {
            // When
            ResilienceContext context = ResilienceContext.builder()
                    .serviceName("resilience-test-service")
                    .platform("Kubernetes")
                    .addChaosExperiment("Network Partition")
                    .addChaosExperiment("Pod Failure")
                    .addChaosExperiment("CPU Stress")
                    .addChaosExperiment("Memory Pressure")
                    .addCircuitBreaker("TestService", java.util.Map.of("status", "closed"))
                    .addRetryPattern("TestRetry", java.util.Map.of("type", "exponential"))
                    .build();

            // Then
            assertThat(context.getChaosExperiments()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("Null Validation Tests")
    class NullValidationTests {

        @Test
        @DisplayName("should throw NPE when adding circuit breaker with null name")
        void shouldThrowNPEWhenAddingCircuitBreakerWithNullName() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .addCircuitBreaker(null, java.util.Map.of("status", "open"))
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Circuit breaker cannot be null");
        }

        @Test
        @DisplayName("should throw NPE when adding circuit breaker with null config")
        void shouldThrowNPEWhenAddingCircuitBreakerWithNullConfig() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .addCircuitBreaker("CB1", null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Circuit breaker config cannot be null");
        }

        @Test
        @DisplayName("should throw NPE when adding retry pattern with null name")
        void shouldThrowNPEWhenAddingRetryPatternWithNullName() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .addRetryPattern(null, java.util.Map.of("type", "exponential"))
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Retry pattern cannot be null");
        }

        @Test
        @DisplayName("should throw NPE when adding retry pattern with null config")
        void shouldThrowNPEWhenAddingRetryPatternWithNullConfig() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .addRetryPattern("Retry1", null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Retry configuration cannot be null");
        }

        @Test
        @DisplayName("should throw NPE when adding backoff strategy with null")
        void shouldThrowNPEWhenAddingBackoffStrategyWithNull() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .addBackoffStrategy(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Backoff strategy cannot be null");
        }

        @Test
        @DisplayName("should throw NPE when adding bulkhead pattern with null")
        void shouldThrowNPEWhenAddingBulkheadPatternWithNull() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .addBulkheadPattern(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Bulkhead pattern cannot be null");
        }

        @Test
        @DisplayName("should throw NPE when adding thread pool with null")
        void shouldThrowNPEWhenAddingThreadPoolWithNull() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .addThreadPool(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Thread pool cannot be null");
        }

        @Test
        @DisplayName("should throw NPE when adding chaos experiment with null")
        void shouldThrowNPEWhenAddingChaosExperimentWithNull() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .addChaosExperiment(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Chaos experiment cannot be null");
        }

        @Test
        @DisplayName("should throw NPE when adding health check with null")
        void shouldThrowNPEWhenAddingHealthCheckWithNull() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .addHealthCheck(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Health check cannot be null");
        }

        @Test
        @DisplayName("should throw NPE when adding SLI/SLO definition with null key")
        void shouldThrowNPEWhenAddingSLISLODefinitionWithNullKey() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .addSliSloDefinition(null, "target=99.9%")
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("SLI/SLO key cannot be null");
        }

        @Test
        @DisplayName("should throw NPE when adding SLI/SLO definition with null value")
        void shouldThrowNPEWhenAddingSLISLODefinitionWithNullValue() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .addSliSloDefinition("Availability", null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("SLI/SLO definition cannot be null");
        }

        @Test
        @DisplayName("should throw NPE when setting service name with null")
        void shouldThrowNPEWhenSettingServiceNameWithNull() {
            ResilienceContext context = ResilienceContext.builder()
                    .serviceName("test-service")
                    .build();

            assertThatThrownBy(() -> context.setServiceName(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Service name cannot be null");
        }

        @Test
        @DisplayName("should throw NPE when setting platform with null")
        void shouldThrowNPEWhenSettingPlatformWithNull() {
            ResilienceContext context = ResilienceContext.builder()
                    .serviceName("test-service")
                    .platform("Kubernetes")
                    .build();

            assertThatThrownBy(() -> context.setPlatform(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Platform cannot be null");
        }

        @Test
        @DisplayName("should throw NPE when setting failure type with null")
        void shouldThrowNPEWhenSettingFailureTypeWithNull() {
            ResilienceContext context = ResilienceContext.builder()
                    .serviceName("test-service")
                    .build();

            assertThatThrownBy(() -> context.setFailureType(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Failure type cannot be null");
        }

        @Test
        @DisplayName("should throw NPE when setting scaling type with null")
        void shouldThrowNPEWhenSettingScalingTypeWithNull() {
            ResilienceContext context = ResilienceContext.builder()
                    .serviceName("test-service")
                    .build();

            assertThatThrownBy(() -> context.setScalingType(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Scaling type cannot be null");
        }

        @Test
        @DisplayName("should throw NPE on builder circuitBreakers with null set")
        void shouldThrowNPEOnDirectAddCircuitBreakersNullSet() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .circuitBreakers(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Circuit breakers cannot be null");
        }

        @Test
        @DisplayName("should throw NPE on builder circuitBreakerConfigurations with null map")
        void shouldThrowNPEOnDirectAddCircuitBreakerConfigurationsNullMap() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .circuitBreakerConfigurations(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Circuit breaker configurations cannot be null");
        }

        @Test
        @DisplayName("should throw NPE on builder retryPatterns with null set")
        void shouldThrowNPEOnDirectAddRetryPatternsNullSet() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .retryPatterns(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Retry patterns cannot be null");
        }

        @Test
        @DisplayName("should throw NPE on builder retryConfigurations with null map")
        void shouldThrowNPEOnDirectAddRetryConfigurationsNullMap() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .retryConfigurations(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Retry configurations cannot be null");
        }

        @Test
        @DisplayName("should throw NPE on builder backoffStrategies with null set")
        void shouldThrowNPEOnDirectAddBackoffStrategiesNullSet() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .backoffStrategies(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Backoff strategies cannot be null");
        }

        @Test
        @DisplayName("should throw NPE on builder bulkheadPatterns with null set")
        void shouldThrowNPEOnDirectAddBulkheadPatternsNullSet() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .bulkheadPatterns(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Bulkhead patterns cannot be null");
        }

        @Test
        @DisplayName("should throw NPE on builder threadPools with null set")
        void shouldThrowNPEOnDirectAddThreadPoolsNullSet() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .threadPools(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Thread pools cannot be null");
        }

        @Test
        @DisplayName("should throw NPE on builder chaosExperiments with null set")
        void shouldThrowNPEOnDirectAddChaosExperimentsNullSet() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .chaosExperiments(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Chaos experiments cannot be null");
        }

        @Test
        @DisplayName("should throw NPE on builder healthChecks with null set")
        void shouldThrowNPEOnDirectAddHealthChecksNullSet() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .healthChecks(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("Health checks cannot be null");
        }

        @Test
        @DisplayName("should throw NPE on builder sliSloDefinitions with null map")
        void shouldThrowNPEOnDirectAddSliSloDefinitionsNullMap() {
            assertThatThrownBy(() -> ResilienceContext.builder()
                    .sliSloDefinitions(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessage("SLI/SLO definitions cannot be null");
        }
    }
}
