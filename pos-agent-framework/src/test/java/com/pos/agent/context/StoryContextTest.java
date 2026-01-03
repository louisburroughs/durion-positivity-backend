package com.pos.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for StoryContext class.
 */
@DisplayName("StoryContext")
class StoryContextTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create StoryContext using builder")
        void shouldCreateStoryContextUsingBuilder() {
            // When
            StoryContext context = StoryContext.builder().build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should set default agentDomain to 'story'")
        void shouldSetDefaultAgentDomain() {
            // When
            StoryContext context = StoryContext.builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("story");
        }

        @Test
        @DisplayName("should set repositoryUrl")
        void shouldSetRepositoryUrl() {
            // When
            StoryContext context = StoryContext.builder()
                    .repositoryUrl("https://github.com/user/repo")
                    .build();

            // Then
            assertThat(context.getRepositoryUrl()).isEqualTo("https://github.com/user/repo");
        }

        @Test
        @DisplayName("should set issueId")
        void shouldSetIssueId() {
            // When
            StoryContext context = StoryContext.builder()
                    .issueId(123L)
                    .build();

            // Then
            assertThat(context.getIssueId()).isEqualTo(123L);
        }

        @Test
        @DisplayName("should set issueTitle")
        void shouldSetIssueTitle() {
            // When
            StoryContext context = StoryContext.builder()
                    .issueTitle("Add user authentication")
                    .build();

            // Then
            assertThat(context.getIssueTitle()).isEqualTo("Add user authentication");
        }

        @Test
        @DisplayName("should set issueBody")
        void shouldSetIssueBody() {
            // When
            StoryContext context = StoryContext.builder()
                    .issueBody("Implement OAuth2 authentication flow")
                    .build();

            // Then
            assertThat(context.getIssueBody()).isEqualTo("Implement OAuth2 authentication flow");
        }

        @Test
        @DisplayName("should set moduleName")
        void shouldSetModuleName() {
            // When
            StoryContext context = StoryContext.builder()
                    .moduleName("auth-module")
                    .build();

            // Then
            assertThat(context.getModuleName()).isEqualTo("auth-module");
        }

        @Test
        @DisplayName("should set dependencies")
        void shouldSetDependencies() {
            // Given
            List<String> deps = Arrays.asList("spring-security", "oauth2-client");

            // When
            StoryContext context = StoryContext.builder()
                    .dependencies(deps)
                    .build();

            // Then
            assertThat(context.getDependencies()).containsExactly("spring-security", "oauth2-client");
        }

        @Test
        @DisplayName("should handle null dependencies list")
        void shouldHandleNullDependenciesList() {
            // When
            StoryContext context = StoryContext.builder()
                    .dependencies(null)
                    .build();

            // Then
            assertThat(context.getDependencies()).isEmpty();
        }

        @Test
        @DisplayName("should initialize with empty dependencies by default")
        void shouldInitializeWithEmptyDependenciesByDefault() {
            // When
            StoryContext context = StoryContext.builder().build();

            // Then
            assertThat(context.getDependencies()).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("Fluent API Tests")
    class FluentAPITests {

        @Test
        @DisplayName("should support fluent chaining with all builder methods")
        void shouldSupportFluentChainingWithAllBuilderMethods() {
            // When
            StoryContext context = StoryContext.builder()
                    .repositoryUrl("https://github.com/org/project")
                    .issueId(456L)
                    .issueTitle("Implement payment gateway")
                    .issueBody("Integrate Stripe payment processing")
                    .moduleName("payment-service")
                    .dependencies(Arrays.asList("stripe-java", "jackson-core"))
                    .description("Payment gateway story context")
                    .build();

            // Then
            assertThat(context.getRepositoryUrl()).isEqualTo("https://github.com/org/project");
            assertThat(context.getIssueId()).isEqualTo(456L);
            assertThat(context.getIssueTitle()).isEqualTo("Implement payment gateway");
            assertThat(context.getIssueBody()).isEqualTo("Integrate Stripe payment processing");
            assertThat(context.getModuleName()).isEqualTo("payment-service");
            assertThat(context.getDependencies()).containsExactly("stripe-java", "jackson-core");
            assertThat(context.getDescription()).isEqualTo("Payment gateway story context");
        }
    }

    @Nested
    @DisplayName("Inheritance Tests")
    class InheritanceTests {

        @Test
        @DisplayName("should inherit from AgentContext")
        void shouldInheritFromAgentContext() {
            // When
            StoryContext context = StoryContext.builder().build();

            // Then
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should have access to AgentContext properties")
        void shouldHaveAccessToAgentContextProperties() {
            // When
            StoryContext context = StoryContext.builder()
                    .property("customKey", "customValue")
                    .build();

            // Then
            assertThat(context.getContextId()).isNotNull();
            assertThat(context.getSessionId()).isNotNull();
            assertThat(context.getProperty("customKey")).isEqualTo("customValue");
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("should handle null repositoryUrl")
        void shouldHandleNullRepositoryUrl() {
            // When
            StoryContext context = StoryContext.builder()
                    .repositoryUrl(null)
                    .build();

            // Then
            assertThat(context.getRepositoryUrl()).isNull();
        }

        @Test
        @DisplayName("should handle null issueId")
        void shouldHandleNullIssueId() {
            // When
            StoryContext context = StoryContext.builder()
                    .issueId(null)
                    .build();

            // Then
            assertThat(context.getIssueId()).isNull();
        }

        @Test
        @DisplayName("should handle empty strings")
        void shouldHandleEmptyStrings() {
            // When
            StoryContext context = StoryContext.builder()
                    .repositoryUrl("")
                    .issueTitle("")
                    .issueBody("")
                    .moduleName("")
                    .build();

            // Then
            assertThat(context.getRepositoryUrl()).isEmpty();
            assertThat(context.getIssueTitle()).isEmpty();
            assertThat(context.getIssueBody()).isEmpty();
            assertThat(context.getModuleName()).isEmpty();
        }

        @Test
        @DisplayName("should handle empty dependencies list")
        void shouldHandleEmptyDependenciesList() {
            // When
            StoryContext context = StoryContext.builder()
                    .dependencies(Arrays.asList())
                    .build();

            // Then
            assertThat(context.getDependencies()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should create context for GitHub issue")
        void shouldCreateContextForGitHubIssue() {
            // When
            StoryContext context = StoryContext.builder()
                    .repositoryUrl("https://github.com/acme/backend")
                    .issueId(789L)
                    .issueTitle("Implement user registration")
                    .issueBody("As a user, I want to register an account...")
                    .moduleName("user-service")
                    .dependencies(Arrays.asList("spring-boot-starter-security", "jjwt"))
                    .build();

            // Then
            assertThat(context.getRepositoryUrl()).contains("github.com");
            assertThat(context.getIssueId()).isPositive();
            assertThat(context.getIssueTitle()).isNotEmpty();
            assertThat(context.getDependencies()).isNotEmpty();
        }

        @Test
        @DisplayName("should create context for minimal story")
        void shouldCreateContextForMinimalStory() {
            // When
            StoryContext context = StoryContext.builder()
                    .issueId(1L)
                    .issueTitle("Fix bug")
                    .build();

            // Then
            assertThat(context.getIssueId()).isEqualTo(1L);
            assertThat(context.getIssueTitle()).isEqualTo("Fix bug");
            assertThat(context.getDependencies()).isEmpty();
        }

        @Test
        @DisplayName("should create context for complex story with multiple dependencies")
        void shouldCreateContextForComplexStory() {
            // Given
            List<String> dependencies = Arrays.asList(
                    "spring-boot-starter-web",
                    "spring-boot-starter-data-jpa",
                    "postgresql",
                    "liquibase-core",
                    "lombok");

            // When
            StoryContext context = StoryContext.builder()
                    .repositoryUrl("https://github.com/enterprise/erp-system")
                    .issueId(2456L)
                    .issueTitle("Implement inventory management")
                    .issueBody("Full inventory tracking with real-time updates")
                    .moduleName("inventory-module")
                    .dependencies(dependencies)
                    .description("Critical feature for Q1 release")
                    .requiresAuthentication(true)
                    .requiresAuditTrail(true)
                    .build();

            // Then
            assertThat(context.getDependencies()).hasSize(5);
            assertThat(context.getProperty("requiresAuthentication")).isEqualTo(true);
            assertThat(context.getProperty("requiresAuditTrail")).isEqualTo(true);
        }
    }
}
