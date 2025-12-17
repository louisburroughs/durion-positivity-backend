package com.positivity.agent.config;

import com.positivity.agent.impl.ArchitectureAgent;
import com.positivity.agent.impl.DeploymentAgent;
import com.positivity.agent.impl.ImplementationAgent;
import com.positivity.agent.impl.TestingAgent;
import com.positivity.agent.registry.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for automatically registering core agents
 */
@Configuration
public class AgentRegistrationConfiguration {
    
    private static final Logger logger = LoggerFactory.getLogger(AgentRegistrationConfiguration.class);
    
    @Bean
    public CommandLineRunner registerCoreAgents(
            AgentRegistry agentRegistry,
            ArchitectureAgent architectureAgent,
            ImplementationAgent implementationAgent,
            DeploymentAgent deploymentAgent,
            TestingAgent testingAgent) {
        
        return args -> {
            logger.info("Registering core agents...");
            
            // Register all core agents
            agentRegistry.registerAgent(architectureAgent);
            agentRegistry.registerAgent(implementationAgent);
            agentRegistry.registerAgent(deploymentAgent);
            agentRegistry.registerAgent(testingAgent);
            
            logger.info("Successfully registered {} core agents", 4);
            
            // Log registry health status
            var healthStatus = agentRegistry.getHealthStatus();
            logger.info("Agent registry health: {} total agents, {} available, {} unhealthy", 
                    healthStatus.totalAgents(), 
                    healthStatus.availableAgents(), 
                    healthStatus.unhealthyAgents());
        };
    }
}