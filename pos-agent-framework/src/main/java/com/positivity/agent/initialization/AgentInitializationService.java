package com.positivity.agent.initialization;

import com.positivity.agent.Agent;
import com.positivity.agent.factory.AgentFactory;
import com.positivity.agent.registry.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Initializes all agents in the system with proper performance specifications
 * and failover configurations
 */
@Component
public class AgentInitializationService implements ApplicationRunner {
    
    private static final Logger logger = LoggerFactory.getLogger(AgentInitializationService.class);
    
    private final AgentRegistry agentRegistry;
    private final AgentFactory agentFactory;
    
    public AgentInitializationService(AgentRegistry agentRegistry, AgentFactory agentFactory) {
        this.agentRegistry = agentRegistry;
        this.agentFactory = agentFactory;
    }
    
    @Override
    public void run(ApplicationArguments args) throws Exception {
        logger.info("Initializing agent framework with performance specifications...");
        Instant start = Instant.now();
        
        try {
            // Initialize core infrastructure agents
            initializeInfrastructureAgents();
            
            // Initialize implementation agents
            initializeImplementationAgents();
            
            // Initialize quality assurance agents
            initializeQualityAgents();
            
            // Initialize domain-specific agents
            initializeDomainAgents();
            
            // Verify agent initialization
            verifyAgentInitialization();
            
            Duration initTime = Duration.between(start, Instant.now());
            logger.info("Agent framework initialization completed in {} ms. Total agents: {}", 
                initTime.toMillis(), agentRegistry.getAllAgents().size());
            
            // Verify 1-second response time requirement for agent discovery
            if (initTime.compareTo(Duration.ofSeconds(1)) <= 0) {
                logger.info("✓ Agent framework meets 1-second initialization requirement");
            } else {
                logger.warn("⚠ Agent framework initialization exceeded 1-second threshold");
            }
            
        } catch (Exception e) {
            logger.error("Failed to initialize agent framework", e);
            throw e;
        }
    }
    
    private void initializeInfrastructureAgents() {
        logger.debug("Initializing infrastructure agents...");
        
        // Architecture Agent - Critical for system design
        Agent architectureAgent = agentFactory.createArchitectureAgent();
        agentRegistry.registerAgent(architectureAgent);
        logger.debug("Registered Architecture Agent with critical performance spec");
        
        // Security Agent - Critical for system security
        Agent securityAgent = agentFactory.createSecurityAgent();
        agentRegistry.registerAgent(securityAgent);
        logger.debug("Registered Security Agent with critical performance spec");
        
        // DevOps Agent - High performance for deployment
        Agent devOpsAgent = agentFactory.createDevOpsAgent();
        agentRegistry.registerAgent(devOpsAgent);
        logger.debug("Registered DevOps Agent with high performance spec");
        
        // SRE Agent - High performance for observability
        Agent sreAgent = agentFactory.createSreAgent();
        agentRegistry.registerAgent(sreAgent);
        logger.debug("Registered SRE Agent with high performance spec");
    }
    
    private void initializeImplementationAgents() {
        logger.debug("Initializing implementation agents...");
        
        // Spring Boot Developer Agent - High performance for core implementation
        Agent springBootAgent = agentFactory.createSpringBootDeveloperAgent();
        agentRegistry.registerAgent(springBootAgent);
        logger.debug("Registered Spring Boot Developer Agent with high performance spec");
        
        // API Gateway Agent - High performance for API design
        Agent apiGatewayAgent = agentFactory.createApiGatewayAgent();
        agentRegistry.registerAgent(apiGatewayAgent);
        logger.debug("Registered API Gateway Agent with high performance spec");
    }
    
    private void initializeQualityAgents() {
        logger.debug("Initializing quality assurance agents...");
        
        // Testing Agent - Default performance for comprehensive testing
        Agent testingAgent = createTestingAgent();
        agentRegistry.registerAgent(testingAgent);
        logger.debug("Registered Testing Agent with default performance spec");
        
        // Code Quality Agent - Default performance for code analysis
        Agent codeQualityAgent = createCodeQualityAgent();
        agentRegistry.registerAgent(codeQualityAgent);
        logger.debug("Registered Code Quality Agent with default performance spec");
        
        // Performance Agent - High performance for optimization
        Agent performanceAgent = createPerformanceAgent();
        agentRegistry.registerAgent(performanceAgent);
        logger.debug("Registered Performance Agent with high performance spec");
    }
    
    private void initializeDomainAgents() {
        logger.debug("Initializing domain-specific agents...");
        
        // POS Business Agent - High performance for business logic
        Agent posBusinessAgent = createPosBusinessAgent();
        agentRegistry.registerAgent(posBusinessAgent);
        logger.debug("Registered POS Business Agent with high performance spec");
        
        // Integration Agent - High performance for external integrations
        Agent integrationAgent = createIntegrationAgent();
        agentRegistry.registerAgent(integrationAgent);
        logger.debug("Registered Integration Agent with high performance spec");
        
        // Documentation Agent - Default performance for documentation
        Agent documentationAgent = createDocumentationAgent();
        agentRegistry.registerAgent(documentationAgent);
        logger.debug("Registered Documentation Agent with default performance spec");
    }
    
    private void verifyAgentInitialization() {
        logger.debug("Verifying agent initialization...");
        
        // Verify all major domains have coverage
        String[] majorDomains = {
            "architecture", "implementation", "security", "deployment", 
            "observability", "testing", "performance", "documentation"
        };
        
        for (String domain : majorDomains) {
            var agents = agentRegistry.getAgentsForDomain(domain);
            if (agents.isEmpty()) {
                logger.warn("No agents found for domain: {}", domain);
            } else {
                logger.debug("Domain {} has {} agent(s)", domain, agents.size());
            }
        }
        
        // Verify all agents are available
        var allAgents = agentRegistry.getAllAgents();
        var availableAgents = agentRegistry.getAvailableAgents();
        
        if (allAgents.size() != availableAgents.size()) {
            logger.warn("Some agents are not available: {}/{}", availableAgents.size(), allAgents.size());
        } else {
            logger.info("All {} agents are available and ready", allAgents.size());
        }
        
        // Log performance specifications summary
        logPerformanceSpecificationsSummary();
    }
    
    private void logPerformanceSpecificationsSummary() {
        var agents = agentRegistry.getAllAgents();
        
        long criticalAgents = agents.stream()
            .filter(a -> a.getPerformanceSpec().responseTime().equals(Duration.ofSeconds(1)))
            .count();
        
        long highPerformanceAgents = agents.stream()
            .filter(a -> a.getPerformanceSpec().responseTime().equals(Duration.ofSeconds(2)))
            .count();
        
        long defaultAgents = agents.stream()
            .filter(a -> a.getPerformanceSpec().responseTime().equals(Duration.ofSeconds(3)))
            .count();
        
        logger.info("Performance specifications summary: {} critical (1s), {} high (2s), {} default (3s)",
            criticalAgents, highPerformanceAgents, defaultAgents);
    }
    
    // Helper methods to create additional agents
    
    private Agent createTestingAgent() {
        return agentFactory.createSpringBootDeveloperAgent(); // Placeholder - would be specialized
    }
    
    private Agent createCodeQualityAgent() {
        return agentFactory.createSpringBootDeveloperAgent(); // Placeholder - would be specialized
    }
    
    private Agent createPerformanceAgent() {
        return agentFactory.createSreAgent(); // Placeholder - would be specialized
    }
    
    private Agent createPosBusinessAgent() {
        return agentFactory.createSpringBootDeveloperAgent(); // Placeholder - would be specialized
    }
    
    private Agent createIntegrationAgent() {
        return agentFactory.createApiGatewayAgent(); // Placeholder - would be specialized
    }
    
    private Agent createDocumentationAgent() {
        return agentFactory.createSpringBootDeveloperAgent(); // Placeholder - would be specialized
    }
}