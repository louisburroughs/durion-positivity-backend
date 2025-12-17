package com.positivity.agent.integration;

import com.positivity.agent.impl.EventDrivenArchitectureAgent;
import com.positivity.agent.impl.CICDPipelineAgent;
import com.positivity.agent.impl.ConfigurationManagementAgent;
import com.positivity.agent.impl.ResilienceEngineeringAgent;
import com.positivity.agent.context.EventDrivenContext;
import com.positivity.agent.context.CICDContext;
import com.positivity.agent.context.ConfigurationContext;
import com.positivity.agent.context.ResilienceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for new agents with AWS services.
 * Validates AWS service integration patterns from enhanced agents.
 */
@SpringBootTest
@ActiveProfiles("test")
class AWSServiceIntegrationTest {

    private EventDrivenArchitectureAgent eventAgent;
    private CICDPipelineAgent cicdAgent;
    private ConfigurationManagementAgent configAgent;
    private ResilienceEngineeringAgent resilienceAgent;

    @BeforeEach
    void setUp() {
        eventAgent = new EventDrivenArchitectureAgent();
        cicdAgent = new CICDPipelineAgent();
        configAgent = new ConfigurationManagementAgent();
        resilienceAgent = new ResilienceEngineeringAgent();
    }

    @Test
    @DisplayName("Event agent provides SNS/SQS integration guidance")
    void testEventAgentSNSSQSIntegration() {
        EventDrivenContext context = new EventDrivenContext();
        context.setEventType("CustomerRegistered");
        context.setServiceName("pos-customer");
        context.setMessagingPlatform("AWS");
        
        String guidance = eventAgent.provideSNSSQSIntegrationGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("SNS"));
        assertTrue(guidance.contains("SQS"));
        assertTrue(guidance.contains("CustomerRegistered"));
        assertTrue(guidance.contains("dead letter queue"));
    }

    @Test
    @DisplayName("CI/CD agent provides CodePipeline integration guidance")
    void testCICDAgentCodePipelineIntegration() {
        CICDContext context = new CICDContext();
        context.setServiceName("pos-inventory");
        context.setDeploymentTarget("AWS");
        context.setPipelineType("CodePipeline");
        
        String guidance = cicdAgent.provideCodePipelineGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("CodePipeline"));
        assertTrue(guidance.contains("CodeBuild"));
        assertTrue(guidance.contains("CodeDeploy"));
        assertTrue(guidance.contains("pos-inventory"));
    }

    @Test
    @DisplayName("Configuration agent provides AWS Secrets Manager integration")
    void testConfigAgentSecretsManagerIntegration() {
        ConfigurationContext context = new ConfigurationContext();
        context.setServiceName("pos-security-service");
        context.setConfigurationType("secrets");
        context.setProvider("AWS");
        
        String guidance = configAgent.provideAWSSecretsManagerGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("AWS Secrets Manager"));
        assertTrue(guidance.contains("rotation"));
        assertTrue(guidance.contains("encryption"));
        assertTrue(guidance.contains("IAM"));
    }

    @Test
    @DisplayName("Resilience agent provides AWS ECS/Fargate resilience patterns")
    void testResilienceAgentECSFargateIntegration() {
        ResilienceContext context = new ResilienceContext();
        context.setServiceName("pos-order");
        context.setPlatform("AWS");
        context.setContainerPlatform("ECS");
        context.setFailureType("container");
        
        String guidance = resilienceAgent.provideECSResilienceGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("ECS"));
        assertTrue(guidance.contains("Fargate"));
        assertTrue(guidance.contains("health check"));
        assertTrue(guidance.contains("auto scaling"));
    }

    @Test
    @DisplayName("Event agent provides EventBridge integration patterns")
    void testEventAgentEventBridgeIntegration() {
        EventDrivenContext context = new EventDrivenContext();
        context.setEventType("OrderStatusChanged");
        context.setServiceName("pos-order");
        context.setMessagingPlatform("EventBridge");
        
        String guidance = eventAgent.provideEventBridgeGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("EventBridge"));
        assertTrue(guidance.contains("event pattern"));
        assertTrue(guidance.contains("OrderStatusChanged"));
        assertTrue(guidance.contains("rule"));
    }

    @Test
    @DisplayName("CI/CD agent provides Lambda deployment strategies")
    void testCICDAgentLambdaDeployment() {
        CICDContext context = new CICDContext();
        context.setServiceName("pos-events");
        context.setDeploymentTarget("Lambda");
        context.setDeploymentStrategy("blue-green");
        
        String guidance = cicdAgent.provideLambdaDeploymentGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("Lambda"));
        assertTrue(guidance.contains("blue-green"));
        assertTrue(guidance.contains("alias"));
        assertTrue(guidance.contains("version"));
    }

    @Test
    @DisplayName("Configuration agent provides Parameter Store integration")
    void testConfigAgentParameterStoreIntegration() {
        ConfigurationContext context = new ConfigurationContext();
        context.setServiceName("pos-catalog");
        context.setConfigurationType("application-config");
        context.setProvider("AWS");
        
        String guidance = configAgent.provideParameterStoreGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("Parameter Store"));
        assertTrue(guidance.contains("hierarchy"));
        assertTrue(guidance.contains("encryption"));
        assertTrue(guidance.contains("pos-catalog"));
    }

    @Test
    @DisplayName("Resilience agent provides RDS failover patterns")
    void testResilienceAgentRDSFailover() {
        ResilienceContext context = new ResilienceContext();
        context.setServiceName("pos-customer");
        context.setFailureType("database");
        context.setPlatform("AWS");
        context.setDatabaseType("RDS");
        
        String guidance = resilienceAgent.provideRDSFailoverGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("RDS"));
        assertTrue(guidance.contains("Multi-AZ"));
        assertTrue(guidance.contains("read replica"));
        assertTrue(guidance.contains("failover"));
    }

    @Test
    @DisplayName("Multi-agent AWS deployment scenario")
    void testMultiAgentAWSDeploymentScenario() {
        String serviceName = "pos-vehicle-fitment";
        
        // Event-driven with EventBridge
        EventDrivenContext eventContext = new EventDrivenContext();
        eventContext.setServiceName(serviceName);
        eventContext.setEventType("FitmentCalculated");
        eventContext.setMessagingPlatform("EventBridge");
        String eventGuidance = eventAgent.provideEventBridgeGuidance(eventContext);
        
        // CI/CD with CodePipeline
        CICDContext cicdContext = new CICDContext();
        cicdContext.setServiceName(serviceName);
        cicdContext.setDeploymentTarget("ECS");
        cicdContext.setPipelineType("CodePipeline");
        String cicdGuidance = cicdAgent.provideCodePipelineGuidance(cicdContext);
        
        // Configuration with Secrets Manager
        ConfigurationContext configContext = new ConfigurationContext();
        configContext.setServiceName(serviceName);
        configContext.setConfigurationType("secrets");
        configContext.setProvider("AWS");
        String configGuidance = configAgent.provideAWSSecretsManagerGuidance(configContext);
        
        // Resilience with ECS
        ResilienceContext resilienceContext = new ResilienceContext();
        resilienceContext.setServiceName(serviceName);
        resilienceContext.setPlatform("AWS");
        resilienceContext.setContainerPlatform("ECS");
        String resilienceGuidance = resilienceAgent.provideECSResilienceGuidance(resilienceContext);
        
        // Verify comprehensive AWS integration
        assertNotNull(eventGuidance);
        assertNotNull(cicdGuidance);
        assertNotNull(configGuidance);
        assertNotNull(resilienceGuidance);
        
        assertTrue(eventGuidance.contains("EventBridge"));
        assertTrue(cicdGuidance.contains("CodePipeline"));
        assertTrue(configGuidance.contains("Secrets Manager"));
        assertTrue(resilienceGuidance.contains("ECS"));
    }
}
