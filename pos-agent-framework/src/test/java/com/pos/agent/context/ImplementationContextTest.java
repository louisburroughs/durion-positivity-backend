package com.pos.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ImplementationContext class.
 * Tests development implementation aspects including:
 * - Components and repositories
 * - Programming languages and frameworks
 * - Development tasks and dependencies
 * - Build tools and project management
 */
@DisplayName("ImplementationContext")
class ImplementationContextTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create ImplementationContext using builder")
        void shouldCreateImplementationContextUsingBuilder() {
            // When
            ImplementationContext context = ImplementationContext.builder().build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should set default agentDomain to 'implementation'")
        void shouldSetDefaultAgentDomain() {
            // When
            ImplementationContext context = ImplementationContext.builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("implementation");
        }

        @Test
        @DisplayName("should set default contextType to 'implementation-context'")
        void shouldSetDefaultContextType() {
            // When
            ImplementationContext context = ImplementationContext.builder().build();

            // Then
            assertThat(context.getContextType()).isEqualTo("implementation-context");
        }

        @Test
        @DisplayName("should add component with status")
        void shouldAddComponent() {
            // When
            ImplementationContext context = ImplementationContext.builder()
                    .addComponent("AuthService", "in-progress")
                    .build();

            // Then
            assertThat(context.getComponents()).contains("AuthService");
            assertThat(context.getComponentStatuses()).containsEntry("AuthService", "in-progress");
        }

        @Test
        @DisplayName("should add repository")
        void shouldAddRepository() {
            // When
            ImplementationContext context = ImplementationContext.builder()
                    .addRepository("https://github.com/org/service", "main")
                    .build();

            // Then
            assertThat(context.getRepositories()).contains("https://github.com/org/service");
        }

        @Test
        @DisplayName("should add branch to repository")
        void shouldAddBranchToRepository() {
            // When
            ImplementationContext context = ImplementationContext.builder()
                    .addRepository("https://github.com/org/service", "develop")
                    .build();

            // Then
            assertThat(context.getRepositories()).contains("https://github.com/org/service");
        }

        @Test
        @DisplayName("should add programming language")
        void shouldAddProgrammingLanguage() {
            // When
            ImplementationContext context = ImplementationContext.builder()
                    .addLanguage("Java")
                    .build();

            // Then
            assertThat(context.getLanguages()).contains("Java");
        }

        @Test
        @DisplayName("should add framework")
        void shouldAddFramework() {
            // When
            ImplementationContext context = ImplementationContext.builder()
                    .addFramework("Spring Boot")
                    .build();

            // Then
            assertThat(context.getFrameworks()).contains("Spring Boot");
        }

        @Test
        @DisplayName("should add task with status")
        void shouldAddTask() {
            // When
            ImplementationContext context = ImplementationContext.builder()
                    .addTask("Implement Login", "completed")
                    .build();

            // Then
            assertThat(context.getTasks()).contains("Implement Login");
            assertThat(context.getTaskStatuses()).containsEntry("Implement Login", "completed");
        }

        @Test
        @DisplayName("should add dependency")
        void shouldAddDependency() {
            // When
            ImplementationContext context = ImplementationContext.builder()
                    .addDependency("commons-lang3", "3.12.0")
                    .build();

            // Then
            assertThat(context.getDependencies()).containsKey("commons-lang3");
        }

        @Test
        @DisplayName("should add build tool")
        void shouldAddBuildTool() {
            // When
            ImplementationContext context = ImplementationContext.builder()
                    .addBuildTool("Maven", "3.8.1")
                    .build();

            // Then
            assertThat(context.getBuildTools()).containsKey("Maven");
        }
    }

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("should return defensive copy of components")
        void shouldReturnDefensiveCopyOfComponents() {
            // Given
            ImplementationContext context = ImplementationContext.builder()
                    .addComponent("Component1", "active")
                    .build();

            // When
            var components = context.getComponents();
            components.add("Hacked");

            // Then
            assertThat(context.getComponents()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of repositories")
        void shouldReturnDefensiveCopyOfRepositories() {
            // Given
            ImplementationContext context = ImplementationContext.builder()
                    .addRepository("https://repo1", "main")
                    .build();

            // When
            var repos = context.getRepositories();
            repos.add("Hacked");

            // Then
            assertThat(context.getRepositories()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of frameworks")
        void shouldReturnDefensiveCopyOfFrameworks() {
            // Given
            ImplementationContext context = ImplementationContext.builder()
                    .addFramework("Framework1")
                    .build();

            // When
            var frameworks = context.getFrameworks();
            frameworks.add("Hacked");

            // Then
            assertThat(context.getFrameworks()).doesNotContain("Hacked");
        }
    }

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should create comprehensive implementation context for Java service")
        void shouldCreateJavaServiceImplementationContext() {
            // When
            ImplementationContext context = ImplementationContext.builder()
                    .description("User Service Implementation Plan")
                    .addComponent("User Controller", "completed")
                    .addComponent("User Service", "completed")
                    .addComponent("User Repository", "completed")
                    .addComponent("User Validation", "in-progress")
                    .addComponent("User Cache", "not-started")
                    .addRepository("https://github.com/org/user-service", "develop")
                    .addLanguage("Java")
                    .addLanguage("SQL")
                    .addFramework("Spring Boot")
                    .addFramework("Spring Data JPA")
                    .addTask("Setup Project", "completed")
                    .addTask("Implement API", "completed")
                    .addTask("Add Tests", "in-progress")
                    .addTask("Write Documentation", "not-started")
                    .addDependency("org.springframework.boot:spring-boot-starter-web", "2.7.0")
                    .addDependency("org.springframework.boot:spring-boot-starter-data-jpa", "2.7.0")
                    .addDependency("com.mysql:mysql-connector-java", "8.0.29")
                    .addBuildTool("Maven", "3.8.1")
                    .build();

            // Then
            assertThat(context.getComponents()).hasSize(5);
            assertThat(context.getLanguages()).hasSize(2);
            assertThat(context.getFrameworks()).hasSize(2);
            assertThat(context.getTasks()).hasSize(4);
            assertThat(context.getDependencies()).hasSize(3);
            assertThat(context.getBuildTools()).hasSize(1);
        }

        @Test
        @DisplayName("should create implementation context with multiple components at different stages")
        void shouldCreateMultiComponentImplementationContext() {
            // When
            ImplementationContext context = ImplementationContext.builder()
                    .description("Microservices Implementation Tracking")
                    .addComponent("Payment Service", "completed")
                    .addComponent("Order Service", "in-progress")
                    .addComponent("Inventory Service", "in-progress")
                    .addComponent("Notification Service", "review")
                    .addComponent("Report Service", "not-started")
                    .addRepository("https://github.com/org/payment-service", "main")
                    .addRepository("https://github.com/org/order-service", "develop")
                    .addRepository("https://github.com/org/inventory-service", "feature/v2")
                    .addLanguage("Java")
                    .addLanguage("Groovy")
                    .addFramework("Spring Boot")
                    .addFramework("Moqui")
                    .addBuildTool("Maven", "3.8.1")
                    .addBuildTool("Gradle", "7.4")
                    .build();

            // Then
            assertThat(context.getComponents()).hasSize(5);
            assertThat(context.getRepositories()).hasSize(3);
            assertThat(context.getLanguages()).hasSize(2);
        }

        @Test
        @DisplayName("should create implementation context with comprehensive task breakdown")
        void shouldCreateComprehensiveTaskBreakdownContext() {
            // When
            ImplementationContext context = ImplementationContext.builder()
                    .description("Feature Implementation Tasks")
                    .addTask("Requirement Analysis", "completed")
                    .addTask("Architecture Design", "completed")
                    .addTask("Database Schema", "in-progress")
                    .addTask("Backend Implementation", "in-progress")
                    .addTask("API Endpoints", "in-progress")
                    .addTask("Frontend Integration", "pending")
                    .addTask("Unit Tests", "pending")
                    .addTask("Integration Tests", "not-started")
                    .addTask("Performance Testing", "not-started")
                    .addTask("Documentation", "not-started")
                    .addTask("Code Review", "not-started")
                    .addTask("Deployment Preparation", "not-started")
                    .addComponent("Feature Module", "in-progress")
                    .addDependency("Feature Library A", "1.0")
                    .addDependency("Feature Library B", "2.1")
                    .build();

            // Then
            assertThat(context.getTasks()).hasSize(12);
            assertThat(context.getTaskStatuses())
                    .containsEntry("Requirement Analysis", "completed")
                    .containsEntry("Database Schema", "in-progress")
                    .containsEntry("Integration Tests", "not-started");
        }

        @Test
        @DisplayName("should create implementation context with multi-language project")
        void shouldCreateMultiLanguageProjectContext() {
            // When
            ImplementationContext context = ImplementationContext.builder()
                    .description("Full-Stack Multi-Language Project")
                    .addLanguage("Java")
                    .addLanguage("TypeScript")
                    .addLanguage("Python")
                    .addLanguage("SQL")
                    .addFramework("Spring Boot")
                    .addFramework("Vue.js")
                    .addFramework("FastAPI")
                    .addRepository("https://github.com/org/backend", "main")
                    .addRepository("https://github.com/org/frontend", "main")
                    .addRepository("https://github.com/org/data-service", "main")
                    .addBuildTool("Maven", "3.8.1")
                    .addBuildTool("npm", "8.0")
                    .addBuildTool("pip", "22.0")
                    .build();

            // Then
            assertThat(context.getLanguages()).hasSize(4);
            assertThat(context.getFrameworks()).hasSize(3);
            assertThat(context.getRepositories()).hasSize(3);
            assertThat(context.getBuildTools()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("Null Validation Tests")
    class NullValidationTests {

        @Test
        @DisplayName("should throw when adding null component")
        void shouldThrowWhenAddingNullComponent() {
            assertThatThrownBy(() -> ImplementationContext.builder()
                    .addComponent(null, "status")
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("component cannot be null");
        }

        @Test
        @DisplayName("should throw when adding component with null status")
        void shouldThrowWhenAddingComponentWithNullStatus() {
            assertThatThrownBy(() -> ImplementationContext.builder()
                    .addComponent("Component1", null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("status cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null repository")
        void shouldThrowWhenAddingNullRepository() {
            assertThatThrownBy(() -> ImplementationContext.builder()
                    .addRepository(null, "main")
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("repository cannot be null");
        }

        @Test
        @DisplayName("should throw when adding repository with null branch")
        void shouldThrowWhenAddingRepositoryWithNullBranch() {
            assertThatThrownBy(() -> ImplementationContext.builder()
                    .addRepository("https://github.com/org/repo", null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("branch cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null language")
        void shouldThrowWhenAddingNullLanguage() {
            assertThatThrownBy(() -> ImplementationContext.builder()
                    .addLanguage(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("language cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null framework")
        void shouldThrowWhenAddingNullFramework() {
            assertThatThrownBy(() -> ImplementationContext.builder()
                    .addFramework(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("framework cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null task")
        void shouldThrowWhenAddingNullTask() {
            assertThatThrownBy(() -> ImplementationContext.builder()
                    .addTask(null, "completed")
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("task cannot be null");
        }

        @Test
        @DisplayName("should throw when adding task with null status")
        void shouldThrowWhenAddingTaskWithNullStatus() {
            assertThatThrownBy(() -> ImplementationContext.builder()
                    .addTask("Task1", null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("status cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null dependency")
        void shouldThrowWhenAddingNullDependency() {
            assertThatThrownBy(() -> ImplementationContext.builder()
                    .addDependency(null, "1.0.0")
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("dependency cannot be null");
        }

        @Test
        @DisplayName("should throw when adding dependency with null version")
        void shouldThrowWhenAddingDependencyWithNullVersion() {
            assertThatThrownBy(() -> ImplementationContext.builder()
                    .addDependency("commons-lang3", null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("version cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null build tool")
        void shouldThrowWhenAddingNullBuildTool() {
            assertThatThrownBy(() -> ImplementationContext.builder()
                    .addBuildTool(null, "3.8.1")
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("tool cannot be null");
        }

        @Test
        @DisplayName("should throw when adding build tool with null version")
        void shouldThrowWhenAddingBuildToolWithNullVersion() {
            assertThatThrownBy(() -> ImplementationContext.builder()
                    .addBuildTool("Maven", null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("version cannot be null");
        }
    }
}
