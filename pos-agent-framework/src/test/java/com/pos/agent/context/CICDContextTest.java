package com.pos.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for CICDContext class.
 * Tests comprehensive CI/CD pipeline management across:
 * - Build automation tools and stages
 * - Testing frameworks and code quality
 * - Security scanning and vulnerability detection
 * - Deployment strategies and orchestration
 * - Triggers and notifications
 */
@DisplayName("CICDContext")
class CICDContextTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create CICDContext using builder")
        void shouldCreateCICDContextUsingBuilder() {
            // When
            CICDContext context = CICDContext.builder().build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should set default agentDomain to 'cicd'")
        void shouldSetDefaultAgentDomain() {
            // When
            CICDContext context = CICDContext.builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("cicd");
        }

        @Test
        @DisplayName("should set default contextType to 'cicd-context'")
        void shouldSetDefaultContextType() {
            // When
            CICDContext context = CICDContext.builder().build();

            // Then
            assertThat(context.getContextType()).isEqualTo("cicd-context");
        }

        @Test
        @DisplayName("should set service name")
        void shouldSetServiceName() {
            // When
            CICDContext context = CICDContext.builder()
                    .serviceName("payment-service")
                    .build();

            // Then
            assertThat(context.getServiceName()).isEqualTo("payment-service");
        }

        @Test
        @DisplayName("should set deployment target")
        void shouldSetDeploymentTarget() {
            // When
            CICDContext context = CICDContext.builder()
                    .deploymentTarget("us-east-1")
                    .build();

            // Then
            assertThat(context.getDeploymentTarget()).isEqualTo("us-east-1");
        }

        @Test
        @DisplayName("should set deployment strategy")
        void shouldSetDeploymentStrategy() {
            // When
            CICDContext context = CICDContext.builder()
                    .deploymentStrategy("Blue-Green")
                    .build();

            // Then
            assertThat(context.getDeploymentStrategy()).isEqualTo("Blue-Green");
        }

        @Test
        @DisplayName("should add build tool")
        void shouldAddBuildTool() {
            // When
            Map<String, Object> mavenConfig = new HashMap<>();
            mavenConfig.put("version", "3.8.1");
            CICDContext context = CICDContext.builder()
                    .addBuildTool("Maven", mavenConfig)
                    .build();

            // Then
            assertThat(context.getBuildTools()).contains("Maven");
            assertThat(context.getBuildConfigurations()).containsKey("Maven");
        }

        @Test
        @DisplayName("should add build stage with configuration")
        void shouldAddBuildStage() {
            // When
            Map<String, Object> compileConfig = new HashMap<>();
            compileConfig.put("target-dir", "/target");
            CICDContext context = CICDContext.builder()
                    .buildStages(Set.of("Compile"))
                    .buildConfigurations(compileConfig)
                    .build();

            // Then
            assertThat(context.getBuildStages()).contains("Compile");
            assertThat(context.getBuildConfigurations()).containsKey("target-dir");
        }

        @Test
        @DisplayName("should add testing framework")
        void shouldAddTestingFramework() {
            // When
            CICDContext context = CICDContext.builder()
                    .testingFrameworks(Set.of("JUnit"))
                    .build();

            // Then
            assertThat(context.getTestingFrameworks()).contains("JUnit");
        }

        @Test
        @DisplayName("should add test type")
        void shouldAddTestType() {
            // When
            CICDContext context = CICDContext.builder()
                    .testTypes(Set.of("Unit Tests"))
                    .build();

            // Then
            assertThat(context.getTestTypes()).contains("Unit Tests");
        }

        @Test
        @DisplayName("should add code quality tool")
        void shouldAddCodeQualityTool() {
            // When
            CICDContext context = CICDContext.builder()
                    .codeQualityTools(Set.of("SonarQube"))
                    .build();

            // Then
            assertThat(context.getCodeQualityTools()).contains("SonarQube");
        }

        @Test
        @DisplayName("should add security scanner")
        void shouldAddSecurityScanner() {
            // When
            CICDContext context = CICDContext.builder()
                    .securityScanners(Set.of("OWASP"))
                    .build();

            // Then
            assertThat(context.getSecurityScanners()).contains("OWASP");
        }

        @Test
        @DisplayName("should add vulnerability check")
        void shouldAddVulnerabilityCheck() {
            // When
            CICDContext context = CICDContext.builder()
                    .securityScanners(Set.of("Snyk"))
                    .vulnerabilityChecks(Set.of("npm-dependencies"))
                    .build();

            // Then
            assertThat(context.getVulnerabilityChecks()).contains("npm-dependencies");
        }

        @Test
        @DisplayName("should add deployment strategy configuration")
        void shouldAddDeploymentStrategyConfig() {
            // When
            Map<String, Object> canaryConfig = new HashMap<>();
            canaryConfig.put("Canary", "duration=5m,error-rate-threshold=5%");
            CICDContext context = CICDContext.builder()
                    .deploymentStrategies(Set.of("Canary"))
                    .deploymentConfigurations(canaryConfig)
                    .build();

            // Then
            assertThat(context.getDeploymentStrategies()).contains("Canary");
            assertThat(context.getDeploymentConfigurations()).containsKey("Canary");
        }

        @Test
        @DisplayName("should add orchestration tool")
        void shouldAddOrchestrationTool() {
            // When
            CICDContext context = CICDContext.builder()
                    .orchestrationTools(Set.of("Kubernetes"))
                    .build();

            // Then
            assertThat(context.getOrchestrationTools()).contains("Kubernetes");
        }
    }

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("should return defensive copy of build tools")
        void shouldReturnDefensiveCopyOfBuildTools() {
            // Given
            Map<String, Object> mavenConfig = new HashMap<>();
            mavenConfig.put("version", "3.8.0");
            CICDContext context = CICDContext.builder()
                    .addBuildTool("Maven", mavenConfig)
                    .build();

            // When
            var tools = context.getBuildTools();
            tools.add("Hacked");

            // Then
            assertThat(context.getBuildTools()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of testing frameworks")
        void shouldReturnDefensiveCopyOfTestingFrameworks() {
            // Given
            CICDContext context = CICDContext.builder()
                    .testingFrameworks(Set.of("JUnit"))
                    .build();

            // When
            var frameworks = context.getTestingFrameworks();
            frameworks.add("Hacked");

            // Then
            assertThat(context.getTestingFrameworks()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of security scanners")
        void shouldReturnDefensiveCopyOfSecurityScanners() {
            // Given
            CICDContext context = CICDContext.builder()
                    .securityScanners(Set.of("OWASP"))
                    .build();

            // When
            var scanners = context.getSecurityScanners();
            scanners.add("Hacked");

            // Then
            assertThat(context.getSecurityScanners()).doesNotContain("Hacked");
        }
    }

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should create comprehensive CI/CD pipeline")
        void shouldCreateComprehensiveCICDPipeline() {
            // When
            Map<String, Object> mavenConfig = new HashMap<>();
            mavenConfig.put("version", "3.8.1");
            CICDContext context = CICDContext.builder()
                    .serviceName("user-service")
                    .deploymentTarget("us-east-1-prod")
                    .deploymentStrategy("Blue-Green")
                    .build();

            context.addBuildTool("Maven", mavenConfig);
            context.addBuildStage("Compile");
            context.addBuildStage("Test");
            context.addBuildStage("Package");
            context.addTestingFramework("JUnit", "5.8.0");
            context.addTestingFramework("Mockito", "4.0");
            context.addTestType("Unit Tests");
            context.addTestType("Integration Tests");
            context.addCodeQualityTool("SonarQube");
            context.addSecurityScanner("OWASP", "5.0");
            context.addSecurityScanner("Snyk", "1.0");
            context.addVulnerabilityCheck("dependency-check");
            context.addVulnerabilityCheck("npm-vulnerabilities");
            Map<String, Object> deployConfig = new HashMap<>();
            deployConfig.put("strategy", "zero-downtime");
            context.addDeploymentStrategy("Blue-Green", deployConfig);
            context.addOrchestrationTool("Kubernetes", new HashMap<>());

            // Then
            assertThat(context.getBuildTools()).hasSize(1);
            assertThat(context.getBuildStages()).hasSize(3);
            assertThat(context.getTestingFrameworks()).hasSize(2);
            assertThat(context.getTestTypes()).hasSize(2);
            assertThat(context.getCodeQualityTools()).hasSize(1);
            assertThat(context.getSecurityScanners()).hasSize(2);
            assertThat(context.getVulnerabilityChecks()).hasSize(2);
            assertThat(context.getDeploymentStrategies()).hasSize(1);
            assertThat(context.getOrchestrationTools()).hasSize(1);
        }

        @Test
        @DisplayName("should create pipeline with multiple deployment strategies")
        void shouldCreatePipelineWithMultipleDeploymentStrategies() {
            // When
            CICDContext context = CICDContext.builder()
                    .serviceName("order-service")
                    .deploymentTarget("prod-cluster")
                    .build();

            Map<String, Object> canaryConfig = new HashMap<>();
            canaryConfig.put("duration", "5m");
            canaryConfig.put("increment", "10%");
            context.addDeploymentStrategy("Canary", canaryConfig);

            Map<String, Object> rollingConfig = new HashMap<>();
            rollingConfig.put("min-ready-seconds", "30");
            context.addDeploymentStrategy("Rolling", rollingConfig);

            Map<String, Object> blueGreenConfig = new HashMap<>();
            blueGreenConfig.put("health-check", "true");
            context.addDeploymentStrategy("Blue-Green", blueGreenConfig);

            // Then
            assertThat(context.getDeploymentStrategies()).hasSize(3);
            assertThat(context.getDeploymentConfigurations()).hasSize(3);
        }

        @Test
        @DisplayName("should create pipeline with comprehensive testing coverage")
        void shouldCreatePipelineWithComprehensiveTestCoverage() {
            // When
            CICDContext context = CICDContext.builder()
                    .serviceName("payment-service")
                    .build();

            context.addTestingFramework("JUnit", "5.8.0");
            context.addTestingFramework("TestNG", "7.4");
            context.addTestingFramework("Cypress", "10.0");
            context.addTestType("Unit Tests");
            context.addTestType("Integration Tests");
            context.addTestType("E2E Tests");
            context.addCodeQualityTool("SonarQube");
            context.addCodeQualityTool("Checkstyle");

            // Then
            assertThat(context.getTestingFrameworks()).hasSize(3);
            assertThat(context.getTestTypes()).hasSize(3);
            assertThat(context.getCodeQualityTools()).hasSize(2);
        }

        @Test
        @DisplayName("should create pipeline with security scanning requirements")
        void shouldCreatePipelineWithSecurityScanning() {
            // When
            CICDContext context = CICDContext.builder()
                    .serviceName("auth-service")
                    .build();

            context.addSecurityScanner("OWASP", "5.0");
            context.addVulnerabilityCheck("dependency-check");
            context.addSecurityScanner("Snyk", "1.0");
            context.addVulnerabilityCheck("container-scanning");
            context.addSecurityScanner("Trivy", "0.20");
            context.addVulnerabilityCheck("image-scanning");

            // Then
            assertThat(context.getSecurityScanners()).hasSize(3);
            assertThat(context.getVulnerabilityChecks()).hasSize(3);
        }
    }
}

@Nested
@DisplayName("Edge Cases Tests")
class EdgeCasesTests {

    @Test
    @DisplayName("should handle empty CICD context")
    void shouldHandleEmptyCICDContext() {
        // When
        CICDContext context = CICDContext.builder().build();

        // Then
        assertThat(context.getBuildTools()).isEmpty();
        assertThat(context.getTestingFrameworks()).isEmpty();
        assertThat(context.getCodeQualityTools()).isEmpty();
        assertThat(context.getSecurityScanners()).isEmpty();
        assertThat(context.getOrchestrationTools()).isEmpty();
    }

    @Test
    @DisplayName("should throw NullPointerException when adding null build tool")
    void shouldThrowWhenAddingNullBuildTool() {
        // Given
        CICDContext context = CICDContext.builder().build();

        // When/Then
        assertThatThrownBy(() -> context.addBuildTool(null, new HashMap<>()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Build tool cannot be null");
    }

    @Test
    @DisplayName("should throw NullPointerException when adding null build tool config")
    void shouldThrowWhenAddingNullBuildToolConfig() {
        // Given
        CICDContext context = CICDContext.builder().build();

        // When/Then
        assertThatThrownBy(() -> context.addBuildTool("Maven", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Build tool config cannot be null");
    }

    @Test
    @DisplayName("should throw NullPointerException when adding null build stage")
    void shouldThrowWhenAddingNullBuildStage() {
        // Given
        CICDContext context = CICDContext.builder().build();

        // When/Then
        assertThatThrownBy(() -> context.addBuildStage(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Build stage cannot be null");
    }

    @Test
    @DisplayName("should throw NullPointerException when setting null service name")
    void shouldThrowWhenSettingNullServiceName() {
        // Given
        CICDContext context = CICDContext.builder().serviceName("old-service").build();

        // When/Then
        assertThatThrownBy(() -> context.setServiceName(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Service name cannot be null");
    }

    @Test
    @DisplayName("should throw NullPointerException when setting null deployment target")
    void shouldThrowWhenSettingNullDeploymentTarget() {
        // Given
        CICDContext context = CICDContext.builder().deploymentTarget("old-target").build();

        // When/Then
        assertThatThrownBy(() -> context.setDeploymentTarget(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Deployment target cannot be null");
    }

    @Test
    @DisplayName("should throw NullPointerException when setting null deployment strategy")
    void shouldThrowWhenSettingNullDeploymentStrategy() {
        // Given
        CICDContext context = CICDContext.builder().deploymentStrategy("Blue-Green").build();

        // When/Then
        assertThatThrownBy(() -> context.setDeploymentStrategy(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Deployment strategy cannot be null");
    }

    @Test
    @DisplayName("should throw NullPointerException when setting null packaging tool")
    void shouldThrowWhenSettingNullPackagingTool() {
        // Given
        CICDContext context = CICDContext.builder().packagingTool("Maven").build();

        // When/Then
        assertThatThrownBy(() -> context.setPackagingTool(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Packaging tool cannot be null");
    }

    @Test
    @DisplayName("should throw NullPointerException when setting null environment")
    void shouldThrowWhenSettingNullEnvironment() {
        // Given
        CICDContext context = CICDContext.builder().environment("production").build();

        // When/Then
        assertThatThrownBy(() -> context.setEnvironment(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Environment cannot be null");
    }
}
