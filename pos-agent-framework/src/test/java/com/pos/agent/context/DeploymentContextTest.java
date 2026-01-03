package com.pos.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for DeploymentContext class.
 */
@DisplayName("DeploymentContext")
class DeploymentContextTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create DeploymentContext using builder")
        void shouldCreateDeploymentContextUsingBuilder() {
            // When
            DeploymentContext context = DeploymentContext.builder().build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should set default agentDomain to 'deployment'")
        void shouldSetDefaultAgentDomain() {
            // When
            DeploymentContext context = DeploymentContext.builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("deployment");
        }

        @Test
        @DisplayName("should set default contextType to 'deployment-context'")
        void shouldSetDefaultContextType() {
            // When
            DeploymentContext context = DeploymentContext.builder().build();

            // Then
            assertThat(context.getContextType()).isEqualTo("deployment-context");
        }

        @Test
        @DisplayName("should add artifact with version")
        void shouldAddArtifactWithVersion() {
            // When
            DeploymentContext context = DeploymentContext.builder()
                    .addArtifact("app-service", "1.0.0")
                    .build();

            // Then
            assertThat(context.getArtifacts()).contains("app-service");
            assertThat(context.getArtifactVersions()).containsEntry("app-service", "1.0.0");
        }

        @Test
        @DisplayName("should add environment")
        void shouldAddEnvironment() {
            // When
            DeploymentContext context = DeploymentContext.builder()
                    .addEnvironment("Production")
                    .build();

            // Then
            assertThat(context.getEnvironments()).contains("Production");
        }

        @Test
        @DisplayName("should add deployment target with strategy")
        void shouldAddDeploymentTarget() {
            // When
            DeploymentContext context = DeploymentContext.builder()
                    .addDeploymentTarget("us-east-1", "Blue-Green")
                    .build();

            // Then
            assertThat(context.getDeploymentTargets()).contains("us-east-1");
            assertThat(context.getTargetStrategies()).containsEntry("us-east-1", "Blue-Green");
        }

        @Test
        @DisplayName("should add approval")
        void shouldAddApproval() {
            // When
            DeploymentContext context = DeploymentContext.builder()
                    .addApproval("Security Team")
                    .build();

            // Then
            assertThat(context.getApprovals()).contains("Security Team");
        }

        @Test
        @DisplayName("should add maintenance window")
        void shouldAddMaintenanceWindow() {
            // When
            DeploymentContext context = DeploymentContext.builder()
                    .addMaintenanceWindow("Saturday 02:00 UTC")
                    .build();

            // Then
            assertThat(context.getMaintenanceWindows()).contains("Saturday 02:00 UTC");
        }
    }

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("should return defensive copy of artifacts")
        void shouldReturnDefensiveCopyOfArtifacts() {
            // Given
            DeploymentContext context = DeploymentContext.builder()
                    .addArtifact("service-1", "1.0.0")
                    .build();

            // When
            var artifacts = context.getArtifacts();
            artifacts.add("Hacked");

            // Then
            assertThat(context.getArtifacts()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of artifact versions")
        void shouldReturnDefensiveCopyOfArtifactVersions() {
            // Given
            DeploymentContext context = DeploymentContext.builder()
                    .addArtifact("service-1", "1.0.0")
                    .build();

            // When
            var versions = context.getArtifactVersions();
            versions.put("Hacked", "9.9.9");

            // Then
            assertThat(context.getArtifactVersions()).doesNotContainKey("Hacked");
        }
    }

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should create comprehensive deployment context")
        void shouldCreateComprehensiveDeploymentContext() {
            // When
            DeploymentContext context = DeploymentContext.builder()
                    .description("Production Deployment Pipeline")
                    .addArtifact("user-service", "2.1.0")
                    .addArtifact("order-service", "1.5.2")
                    .addArtifact("payment-service", "3.0.1")
                    .addEnvironment("Development")
                    .addEnvironment("Staging")
                    .addEnvironment("Production")
                    .addDeploymentTarget("us-east-1", "Blue-Green")
                    .addDeploymentTarget("eu-west-1", "Canary")
                    .addDeploymentTarget("ap-southeast-1", "Rolling")
                    .addApproval("Security Team")
                    .addApproval("DevOps Lead")
                    .addApproval("Release Manager")
                    .addMaintenanceWindow("Saturday 02:00 UTC")
                    .addMaintenanceWindow("Sunday 03:00 UTC")
                    .build();

            // Then
            assertThat(context.getArtifacts()).hasSize(3);
            assertThat(context.getArtifactVersions()).hasSize(3);
            assertThat(context.getEnvironments()).hasSize(3);
            assertThat(context.getDeploymentTargets()).hasSize(3);
            assertThat(context.getTargetStrategies()).hasSize(3);
            assertThat(context.getApprovals()).hasSize(3);
            assertThat(context.getMaintenanceWindows()).hasSize(2);
        }

        @Test
        @DisplayName("should create deployment context for multi-region deployment")
        void shouldCreateMultiRegionDeploymentContext() {
            // When
            DeploymentContext context = DeploymentContext.builder()
                    .description("Multi-Region Global Deployment")
                    .addArtifact("api-gateway", "4.2.0")
                    .addEnvironment("Production")
                    .addDeploymentTarget("us-west-2", "Blue-Green")
                    .addDeploymentTarget("eu-central-1", "Blue-Green")
                    .addDeploymentTarget("ap-northeast-1", "Blue-Green")
                    .addApproval("Global Deployment Team")
                    .addMaintenanceWindow("First Saturday 02:00 UTC")
                    .build();

            // Then
            assertThat(context.getDeploymentTargets())
                    .containsExactlyInAnyOrder("us-west-2", "eu-central-1", "ap-northeast-1");
            assertThat(context.getTargetStrategies().values())
                    .containsOnly("Blue-Green");
        }

        @Test
        @DisplayName("should create deployment context with approval workflow")
        void shouldCreateDeploymentContextWithApprovals() {
            // When
            DeploymentContext context = DeploymentContext.builder()
                    .description("Approval-Required Deployment")
                    .addArtifact("critical-service", "1.0.0")
                    .addEnvironment("Production")
                    .addDeploymentTarget("prod-cluster", "Blue-Green")
                    .addApproval("Security Review")
                    .addApproval("QA Sign-off")
                    .addApproval("Compliance Check")
                    .addApproval("Finance Sign-off")
                    .build();

            // Then
            assertThat(context.getApprovals()).hasSize(4);
            assertThat(context.getApprovals())
                    .contains("Security Review", "QA Sign-off", "Compliance Check", "Finance Sign-off");
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("should handle empty deployment context")
        void shouldHandleEmptyDeploymentContext() {
            // When
            DeploymentContext context = DeploymentContext.builder().build();

            // Then
            assertThat(context.getArtifacts()).isEmpty();
            assertThat(context.getArtifactVersions()).isEmpty();
            assertThat(context.getEnvironments()).isEmpty();
            assertThat(context.getDeploymentTargets()).isEmpty();
            assertThat(context.getApprovals()).isEmpty();
            assertThat(context.getMaintenanceWindows()).isEmpty();
        }

        @Test
        @DisplayName("should handle artifact without version")
        void shouldHandleArtifactWithoutVersion() {
            // When
            DeploymentContext context = DeploymentContext.builder()
                    .addArtifact("service", null)
                    .build();

            // Then
            assertThat(context.getArtifacts()).contains("service");
            assertThat(context.getArtifactVersions()).isEmpty();
        }

        @Test
        @DisplayName("should handle deployment target without strategy")
        void shouldHandleDeploymentTargetWithoutStrategy() {
            // When
            DeploymentContext context = DeploymentContext.builder()
                    .addDeploymentTarget("cluster", null)
                    .build();

            // Then
            assertThat(context.getDeploymentTargets()).contains("cluster");
            assertThat(context.getTargetStrategies()).isEmpty();
        }
    }
}
