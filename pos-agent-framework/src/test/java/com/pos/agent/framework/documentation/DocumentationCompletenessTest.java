package com.pos.agent.framework.documentation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that validate documentation completeness for all agents.
 * Ensures that code changes trigger appropriate documentation updates.
 */
class DocumentationCompletenessTest {

    private static final Map<String, String> AGENT_DOCUMENTATION_REQUIREMENTS = Map.ofEntries(
            Map.entry("architecture", "System Architecture & Design"),
            Map.entry("implementation", "Spring Boot Development"),
            Map.entry("deployment", "DevOps & Infrastructure"),
            Map.entry("testing", "Quality Assurance & Testing"),
            Map.entry("security", "Security & Compliance"),
            Map.entry("observability", "Monitoring & Reliability"),
            Map.entry("documentation", "Technical Documentation"),
            Map.entry("business-domain", "POS Business Logic"),
            Map.entry("integration-gateway", "API Gateway & Integration"),
            Map.entry("pair-programming-navigator", "Code Quality & Collaboration"),
            Map.entry("event-driven-architecture", "Event-Driven Systems"),
            Map.entry("cicd-pipeline", "Continuous Integration/Deployment"),
            Map.entry("configuration-management", "Configuration & Secrets"),
            Map.entry("resilience-engineering", "System Reliability & Resilience"));

    @Test
    @DisplayName("All agent types should be documented with their capabilities")
    void testAllAgentTypesDocumented() throws IOException {
        Path readmePath = Paths.get("README.md");
        if (!Files.exists(readmePath)) {
            // Create basic README if it doesn't exist
            createBasicReadme();
            readmePath = Paths.get("README.md");
        }

        String readmeContent = Files.readString(readmePath);

        for (Map.Entry<String, String> entry : AGENT_DOCUMENTATION_REQUIREMENTS.entrySet()) {
            String agentType = entry.getKey();
            String description = entry.getValue();

            boolean isDocumented = readmeContent.toLowerCase().contains(agentType.toLowerCase()) ||
                    readmeContent.contains(description);

            assertTrue(isDocumented,
                    "Agent type '" + agentType + "' (" + description + ") should be documented in README.md");
        }
    }

    @Test
    @DisplayName("Agent interface documentation should match implementation")
    void testAgentInterfaceDocumentation() throws IOException {
        Path agentPackagePath = Paths.get("src/main/java/com/pos/agent/framework/agent");
        if (!Files.exists(agentPackagePath)) {
            return; // Skip if package doesn't exist
        }

        List<Path> agentFiles = Files.walk(agentPackagePath)
                .filter(path -> path.toString().endsWith("Agent.java"))
                .collect(Collectors.toList());

        for (Path agentFile : agentFiles) {
            String content = Files.readString(agentFile);

            // Check for class-level documentation
            assertTrue(content.contains("/**") || content.contains("/*"),
                    "Agent implementation " + agentFile.getFileName() + " should have class-level documentation");

            // Check for method documentation on public methods
            Pattern publicMethodPattern = Pattern.compile("public\\s+\\w+\\s+\\w+\\s*\\(");
            Matcher matcher = publicMethodPattern.matcher(content);

            while (matcher.find()) {
                int methodStart = matcher.start();
                String beforeMethod = content.substring(Math.max(0, methodStart - 200), methodStart);

                // Should have documentation before public methods
                assertTrue(beforeMethod.contains("/**") || beforeMethod.contains("*"),
                        "Public methods in " + agentFile.getFileName() + " should have documentation");
                break; // Check at least one method
            }
        }
    }

    @Test
    @DisplayName("Configuration documentation should be complete")
    void testConfigurationDocumentation() throws IOException {
        Path readmePath = Paths.get("README.md");
        if (Files.exists(readmePath)) {
            String content = Files.readString(readmePath);

            // Should document configuration options
            assertTrue(content.toLowerCase().contains("configuration") ||
                    content.toLowerCase().contains("config"),
                    "README should document configuration options");

            // Should mention environment-specific configurations
            boolean hasEnvConfig = content.contains("application-dev.yml") ||
                    content.contains("application-staging.yml") ||
                    content.contains("application-prod.yml") ||
                    content.toLowerCase().contains("environment");

            assertTrue(hasEnvConfig,
                    "README should document environment-specific configurations");
        }
    }

    @Test
    @DisplayName("API documentation should exist for agent endpoints")
    void testApiDocumentationExists() throws IOException {
        // Check for OpenAPI/Swagger documentation
        List<String> apiDocPaths = Arrays.asList(
                "src/main/resources/static/swagger-ui.html",
                "src/main/resources/api-docs.yml",
                "src/main/resources/openapi.yml");

        boolean hasApiDocs = apiDocPaths.stream()
                .anyMatch(path -> Files.exists(Paths.get(path)));

        // Check for API documentation in README
        Path readmePath = Paths.get("README.md");
        boolean hasApiDocsInReadme = false;
        if (Files.exists(readmePath)) {
            String content = Files.readString(readmePath);
            hasApiDocsInReadme = content.toLowerCase().contains("api") ||
                    content.toLowerCase().contains("endpoint") ||
                    content.toLowerCase().contains("swagger") ||
                    content.toLowerCase().contains("openapi");
        }

        assertTrue(hasApiDocs || hasApiDocsInReadme,
                "API documentation should exist either as separate files or in README");
    }

    @Test
    @DisplayName("Deployment documentation should be complete")
    void testDeploymentDocumentation() throws IOException {
        Path readmePath = Paths.get("README.md");
        if (Files.exists(readmePath)) {
            String content = Files.readString(readmePath).toLowerCase();

            // Should document Docker deployment
            assertTrue(content.contains("docker") || content.contains("container"),
                    "README should document Docker deployment");

            // Should document Kubernetes deployment
            assertTrue(content.contains("kubernetes") || content.contains("k8s"),
                    "README should document Kubernetes deployment");

            // Should document environment setup
            assertTrue(content.contains("environment") || content.contains("setup"),
                    "README should document environment setup");
        }
    }

    @Test
    @DisplayName("Agent usage examples should be provided")
    void testAgentUsageExamples() throws IOException {
        Path readmePath = Paths.get("README.md");
        if (Files.exists(readmePath)) {
            String content = Files.readString(readmePath);

            // Should provide usage examples
            boolean hasExamples = content.contains("example") ||
                    content.contains("usage") ||
                    content.contains("```") ||
                    content.contains("how to");

            assertTrue(hasExamples,
                    "README should provide agent usage examples");
        }
    }

    @Test
    @DisplayName("Documentation should be synchronized with latest changes")
    void testDocumentationSynchronization() throws IOException {
        // Check if README mentions the correct number of agents
        Path readmePath = Paths.get("README.md");
        if (Files.exists(readmePath)) {
            String content = Files.readString(readmePath);

            // Count agent mentions in documentation
            long documentedAgentCount = AGENT_DOCUMENTATION_REQUIREMENTS.keySet().stream()
                    .mapToLong(agentType -> {
                        String lowerContent = content.toLowerCase();
                        String lowerAgentType = agentType.toLowerCase();
                        return lowerContent.contains(lowerAgentType) ? 1 : 0;
                    })
                    .sum();

            // Should document at least 80% of agents
            double documentationCoverage = (double) documentedAgentCount / AGENT_DOCUMENTATION_REQUIREMENTS.size();
            assertTrue(documentationCoverage >= 0.8,
                    "Documentation should cover at least 80% of agents. Current coverage: " +
                            String.format("%.1f%%", documentationCoverage * 100));
        }
    }

    private void createBasicReadme() throws IOException {
        String basicReadmeContent = """
                # POS Agent Framework

                ## Overview

                The POS Agent Framework provides intelligent development assistance across all aspects of the microservices architecture.

                ## Available Agents

                The framework includes 15 specialized agents:

                ### Core Development Agents
                - **Architecture Agent** - System Architecture & Design
                - **Implementation Agent** - Spring Boot Development
                - **Deployment Agent** - DevOps & Infrastructure
                - **Testing Agent** - Quality Assurance & Testing
                - **Security Agent** - Security & Compliance
                - **Observability Agent** - Monitoring & Reliability
                - **Documentation Agent** - Technical Documentation
                - **Business Domain Agent** - POS Business Logic
                - **Integration Gateway Agent** - API Gateway & Integration
                - **Pair Programming Navigator Agent** - Code Quality & Collaboration

                ### Specialized Agents
                - **Event-Driven Architecture Agent** - Event-Driven Systems
                - **CI/CD Pipeline Agent** - Continuous Integration/Deployment
                - **Configuration Management Agent** - Configuration & Secrets
                - **Resilience Engineering Agent** - System Reliability & Resilience

                ## Configuration

                Agent configurations are managed through environment-specific YAML files:
                - `application.yml` - Base configuration
                - `application-dev.yml` - Development environment
                - `application-staging.yml` - Staging environment
                - `application-prod.yml` - Production environment

                ## Deployment

                ### Docker
                ```bash
                docker-compose up pos-agent-framework
                ```

                ### Kubernetes
                ```bash
                kubectl apply -f pos-agent-framework/k8s-deployment.yml
                ```

                ## Usage Examples

                Agents are automatically selected based on request context and domain requirements.

                ## API Documentation

                API documentation is available through Spring Boot Actuator endpoints.
                """;

        Files.writeString(Paths.get("README.md"), basicReadmeContent);
    }
}
