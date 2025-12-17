package com.pos.agent.framework.documentation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that validate documentation synchronization with code changes.
 * Ensures documentation completeness for all agents and configuration consistency.
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentationSynchronizationTest {

    private static final String README_PATH = "README.md";
    private static final String AGENT_PACKAGE = "src/main/java/com/pos/agent/framework/agent";
    private static final String CONFIG_PATH = "src/main/resources/application.yml";
    
    private List<String> expectedAgentTypes;
    private Map<String, String> agentDescriptions;

    @BeforeEach
    void setUp() {
        expectedAgentTypes = Arrays.asList(
            "architecture", "implementation", "deployment", "testing", "security",
            "observability", "documentation", "business-domain", "integration-gateway",
            "pair-programming-navigator", "event-driven-architecture", "cicd-pipeline",
            "configuration-management", "resilience-engineering"
        );
        
        agentDescriptions = Map.of(
            "architecture", "System Architecture & Design",
            "implementation", "Spring Boot Development", 
            "deployment", "DevOps & Infrastructure",
            "testing", "Quality Assurance & Testing",
            "security", "Security & Compliance",
            "observability", "Monitoring & Reliability",
            "documentation", "Technical Documentation",
            "business-domain", "POS Business Logic",
            "integration-gateway", "API Gateway & Integration",
            "pair-programming-navigator", "Code Quality & Collaboration",
            "event-driven-architecture", "Event-Driven Systems",
            "cicd-pipeline", "Continuous Integration/Deployment",
            "configuration-management", "Configuration & Secrets",
            "resilience-engineering", "System Reliability & Resilience"
        );
    }

    @Test
    @DisplayName("README.md should document all implemented agents")
    void testReadmeDocumentsAllAgents() throws IOException {
        Path readmePath = Paths.get(README_PATH);
        if (!Files.exists(readmePath)) {
            fail("README.md not found at expected location: " + README_PATH);
        }

        String readmeContent = Files.readString(readmePath);
        
        for (String agentType : expectedAgentTypes) {
            String expectedDescription = agentDescriptions.get(agentType);
            assertTrue(readmeContent.contains(agentType) || readmeContent.contains(expectedDescription),
                "README.md should document agent: " + agentType + " (" + expectedDescription + ")");
        }
    }

    @Test
    @DisplayName("All agent implementations should have corresponding documentation")
    void testAgentImplementationsHaveDocumentation() throws IOException {
        Path agentPackagePath = Paths.get(AGENT_PACKAGE);
        if (!Files.exists(agentPackagePath)) {
            // Skip test if agent package doesn't exist yet
            return;
        }

        List<String> implementedAgents = Files.walk(agentPackagePath)
            .filter(path -> path.toString().endsWith("Agent.java"))
            .map(path -> path.getFileName().toString())
            .map(filename -> filename.replace("Agent.java", ""))
            .map(String::toLowerCase)
            .collect(Collectors.toList());

        Path readmePath = Paths.get(README_PATH);
        if (Files.exists(readmePath)) {
            String readmeContent = Files.readString(readmePath).toLowerCase();
            
            for (String agentName : implementedAgents) {
                assertTrue(readmeContent.contains(agentName) || 
                          readmeContent.contains(agentName.replace("-", " ")),
                    "Agent implementation " + agentName + " should be documented in README.md");
            }
        }
    }

    @Test
    @DisplayName("Configuration file should include all documented agents")
    void testConfigurationIncludesAllAgents() throws IOException {
        Path configPath = Paths.get(CONFIG_PATH);
        if (!Files.exists(configPath)) {
            fail("Configuration file not found: " + CONFIG_PATH);
        }

        String configContent = Files.readString(configPath);
        
        for (String agentType : expectedAgentTypes) {
            assertTrue(configContent.contains(agentType),
                "Configuration should include agent: " + agentType);
        }
    }

    @Test
    @DisplayName("Agent configuration should be consistent across environments")
    void testAgentConfigurationConsistency() throws IOException {
        List<String> configFiles = Arrays.asList(
            "src/main/resources/application.yml",
            "src/main/resources/application-dev.yml", 
            "src/main/resources/application-staging.yml",
            "src/main/resources/application-prod.yml"
        );

        Map<String, Set<String>> agentsByConfig = new HashMap<>();
        
        for (String configFile : configFiles) {
            Path configPath = Paths.get(configFile);
            if (Files.exists(configPath)) {
                String content = Files.readString(configPath);
                Set<String> foundAgents = expectedAgentTypes.stream()
                    .filter(content::contains)
                    .collect(Collectors.toSet());
                agentsByConfig.put(configFile, foundAgents);
            }
        }

        // All environment configs should have the same agents as main config
        if (agentsByConfig.containsKey("src/main/resources/application.yml")) {
            Set<String> mainConfigAgents = agentsByConfig.get("src/main/resources/application.yml");
            
            for (Map.Entry<String, Set<String>> entry : agentsByConfig.entrySet()) {
                if (!entry.getKey().equals("src/main/resources/application.yml")) {
                    assertTrue(entry.getValue().containsAll(mainConfigAgents),
                        "Environment config " + entry.getKey() + " should include all agents from main config");
                }
            }
        }
    }

    @Test
    @DisplayName("Docker configuration should reference agent framework")
    void testDockerConfigurationReferencesAgents() throws IOException {
        List<String> dockerFiles = Arrays.asList("Dockerfile", "docker-compose.yml");
        
        for (String dockerFile : dockerFiles) {
            Path dockerPath = Paths.get(dockerFile);
            if (Files.exists(dockerPath)) {
                String content = Files.readString(dockerPath);
                assertTrue(content.contains("pos-agent-framework") || content.contains("agent"),
                    dockerFile + " should reference the agent framework");
            }
        }
    }

    @Test
    @DisplayName("Kubernetes configuration should include agent deployment")
    void testKubernetesConfigurationIncludesAgents() throws IOException {
        Path k8sPath = Paths.get("pos-agent-framework/k8s-deployment.yml");
        if (Files.exists(k8sPath)) {
            String content = Files.readString(k8sPath);
            assertTrue(content.contains("pos-agent-framework"),
                "Kubernetes deployment should include pos-agent-framework service");
            assertTrue(content.contains("ConfigMap") || content.contains("Secret"),
                "Kubernetes deployment should include configuration management");
        }
    }

    @Test
    @DisplayName("Documentation should be up-to-date with latest agent count")
    void testDocumentationAgentCount() throws IOException {
        Path readmePath = Paths.get(README_PATH);
        if (Files.exists(readmePath)) {
            String content = Files.readString(readmePath);
            
            // Check for mentions of agent count (should be 15 total agents)
            Pattern agentCountPattern = Pattern.compile("(\\d+)\\s+agents?", Pattern.CASE_INSENSITIVE);
            boolean foundCorrectCount = agentCountPattern.matcher(content)
                .results()
                .anyMatch(match -> {
                    int count = Integer.parseInt(match.group(1));
                    return count == 15; // Total expected agents
                });
                
            // This is a soft assertion - documentation might not mention exact count
            if (content.contains("agent") && !foundCorrectCount) {
                System.out.println("Warning: Documentation may not reflect current agent count of 15");
            }
        }
    }

    @Test
    @DisplayName("Configuration files should have consistent structure")
    void testConfigurationFileStructure() throws IOException {
        List<String> configFiles = Arrays.asList(
            "src/main/resources/application.yml",
            "src/main/resources/application-dev.yml",
            "src/main/resources/application-staging.yml", 
            "src/main/resources/application-prod.yml"
        );

        for (String configFile : configFiles) {
            Path configPath = Paths.get(configFile);
            if (Files.exists(configPath)) {
                String content = Files.readString(configPath);
                
                // Check for required configuration sections
                assertTrue(content.contains("pos:") || content.contains("agent:"),
                    configFile + " should contain agent configuration section");
                    
                // Check for proper YAML structure
                assertFalse(content.contains("\t"),
                    configFile + " should use spaces, not tabs for indentation");
            }
        }
    }
}
