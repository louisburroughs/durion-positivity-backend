package com.pos.agent.framework.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that validate configuration file consistency across environments.
 * Ensures all agent configurations are properly structured and complete.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfigurationConsistencyTest {

    private static final List<String> REQUIRED_AGENT_TYPES = Arrays.asList(
        "architecture", "implementation", "deployment", "testing", "security",
        "observability", "documentation", "business-domain", "integration-gateway", 
        "pair-programming-navigator", "event-driven-architecture", "cicd-pipeline",
        "configuration-management", "resilience-engineering"
    );

    @Test
    @DisplayName("All configuration files should be valid YAML")
    void testConfigurationFilesAreValidYaml() throws IOException {
        List<String> configFiles = Arrays.asList(
            "src/main/resources/application.yml",
            "src/main/resources/application-dev.yml",
            "src/main/resources/application-staging.yml",
            "src/main/resources/application-prod.yml"
        );

        Yaml yaml = new Yaml();
        
        for (String configFile : configFiles) {
            Path configPath = Paths.get(configFile);
            if (Files.exists(configPath)) {
                String content = Files.readString(configPath);
                assertDoesNotThrow(() -> yaml.load(content),
                    "Configuration file should be valid YAML: " + configFile);
            }
        }
    }

    @Test
    @DisplayName("Main configuration should include all required agent types")
    void testMainConfigurationIncludesAllAgents() throws IOException {
        Path mainConfigPath = Paths.get("src/main/resources/application.yml");
        if (!Files.exists(mainConfigPath)) {
            fail("Main configuration file not found: application.yml");
        }

        String content = Files.readString(mainConfigPath);
        Yaml yaml = new Yaml();
        Map<String, Object> config = yaml.load(content);

        // Navigate to agent configuration
        Map<String, Object> posConfig = getNestedMap(config, "pos");
        if (posConfig != null) {
            Map<String, Object> agentConfig = getNestedMap(posConfig, "agent");
            if (agentConfig != null) {
                Map<String, Object> agentsConfig = getNestedMap(agentConfig, "agents");
                if (agentsConfig != null) {
                    for (String requiredAgent : REQUIRED_AGENT_TYPES) {
                        assertTrue(agentsConfig.containsKey(requiredAgent),
                            "Main configuration should include agent: " + requiredAgent);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Agent configurations should have required properties")
    void testAgentConfigurationsHaveRequiredProperties() throws IOException {
        Path mainConfigPath = Paths.get("src/main/resources/application.yml");
        if (!Files.exists(mainConfigPath)) {
            return; // Skip if config doesn't exist
        }

        String content = Files.readString(mainConfigPath);
        Yaml yaml = new Yaml();
        Map<String, Object> config = yaml.load(content);

        Map<String, Object> agentsConfig = getAgentsConfig(config);
        if (agentsConfig != null) {
            for (String agentType : REQUIRED_AGENT_TYPES) {
                if (agentsConfig.containsKey(agentType)) {
                    Map<String, Object> agentConfig = getNestedMap(agentsConfig, agentType);
                    if (agentConfig != null) {
                        assertTrue(agentConfig.containsKey("enabled"),
                            "Agent " + agentType + " should have 'enabled' property");
                        assertTrue(agentConfig.containsKey("max-instances"),
                            "Agent " + agentType + " should have 'max-instances' property");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Environment-specific configurations should override base configuration")
    void testEnvironmentConfigurationsOverrideBase() throws IOException {
        Map<String, String> envConfigs = Map.of(
            "dev", "src/main/resources/application-dev.yml",
            "staging", "src/main/resources/application-staging.yml", 
            "prod", "src/main/resources/application-prod.yml"
        );

        Yaml yaml = new Yaml();
        
        for (Map.Entry<String, String> entry : envConfigs.entrySet()) {
            Path envConfigPath = Paths.get(entry.getValue());
            if (Files.exists(envConfigPath)) {
                String content = Files.readString(envConfigPath);
                Map<String, Object> envConfig = yaml.load(content);
                
                // Environment configs should have some environment-specific settings
                assertNotNull(envConfig, "Environment config should not be empty: " + entry.getKey());
                
                // Check for environment-specific overrides
                if (envConfig.containsKey("logging")) {
                    Map<String, Object> logging = getNestedMap(envConfig, "logging");
                    assertNotNull(logging, "Environment config should have logging configuration");
                }
            }
        }
    }

    @Test
    @DisplayName("Docker Compose configuration should be consistent with application config")
    void testDockerComposeConsistency() throws IOException {
        Path dockerComposePath = Paths.get("docker-compose.yml");
        if (Files.exists(dockerComposePath)) {
            String content = Files.readString(dockerComposePath);
            
            // Should reference pos-agent-framework service
            assertTrue(content.contains("pos-agent-framework"),
                "Docker Compose should include pos-agent-framework service");
                
            // Should have proper environment configuration
            assertTrue(content.contains("SPRING_PROFILES_ACTIVE") || content.contains("environment"),
                "Docker Compose should configure Spring profiles");
        }
    }

    @Test
    @DisplayName("Kubernetes configuration should be consistent with application config")
    void testKubernetesConsistency() throws IOException {
        Path k8sPath = Paths.get("pos-agent-framework/k8s-deployment.yml");
        if (Files.exists(k8sPath)) {
            String content = Files.readString(k8sPath);
            
            // Should include proper resource definitions
            assertTrue(content.contains("Deployment") || content.contains("Service"),
                "Kubernetes config should include Deployment or Service definitions");
                
            // Should reference the correct image
            assertTrue(content.contains("pos-agent-framework"),
                "Kubernetes config should reference pos-agent-framework image");
        }
    }

    @Test
    @DisplayName("Configuration should not contain sensitive information")
    void testConfigurationSecurityCompliance() throws IOException {
        List<String> configFiles = Arrays.asList(
            "src/main/resources/application.yml",
            "src/main/resources/application-dev.yml",
            "src/main/resources/application-staging.yml",
            "src/main/resources/application-prod.yml"
        );

        List<String> sensitivePatterns = Arrays.asList(
            "password", "secret", "key", "token", "credential"
        );

        for (String configFile : configFiles) {
            Path configPath = Paths.get(configFile);
            if (Files.exists(configPath)) {
                String content = Files.readString(configPath).toLowerCase();
                
                for (String pattern : sensitivePatterns) {
                    if (content.contains(pattern + ":")) {
                        // Check if it's using environment variables or placeholders
                        assertTrue(content.contains("${") || content.contains("ENC("),
                            "Configuration file " + configFile + " should use environment variables or encryption for sensitive values");
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getNestedMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return (value instanceof Map) ? (Map<String, Object>) value : null;
    }

    private Map<String, Object> getAgentsConfig(Map<String, Object> config) {
        Map<String, Object> posConfig = getNestedMap(config, "pos");
        if (posConfig != null) {
            Map<String, Object> agentConfig = getNestedMap(posConfig, "agent");
            if (agentConfig != null) {
                return getNestedMap(agentConfig, "agents");
            }
        }
        return null;
    }
}
