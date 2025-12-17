package com.positivity.agent.integration;

import com.positivity.agent.impl.CICDPipelineAgent;
import com.positivity.agent.impl.ConfigurationManagementAgent;
import com.positivity.agent.impl.ResilienceEngineeringAgent;
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
 * Integration tests for Kubernetes deployment guidance from enhanced agents.
 * Tests Kubernetes deployment patterns and configurations.
 */
@SpringBootTest
@ActiveProfiles("test")
class KubernetesDeploymentIntegrationTest {

    private CICDPipelineAgent cicdAgent;
    private ConfigurationManagementAgent configAgent;
    private ResilienceEngineeringAgent resilienceAgent;

    @BeforeEach
    void setUp() {
        cicdAgent = new CICDPipelineAgent();
        configAgent = new ConfigurationManagementAgent();
        resilienceAgent = new ResilienceEngineeringAgent();
    }

    @Test
    @DisplayName("CI/CD agent provides Kubernetes deployment strategies")
    void testKubernetesDeploymentStrategies() {
        CICDContext context = new CICDContext();
        context.setServiceName("pos-api-gateway");
        context.setDeploymentTarget("kubernetes");
        context.setDeploymentStrategy("rolling");
        
        String guidance = cicdAgent.provideKubernetesDeploymentGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("Kubernetes"));
        assertTrue(guidance.contains("rolling update"));
        assertTrue(guidance.contains("deployment"));
        assertTrue(guidance.contains("replica"));
    }

    @Test
    @DisplayName("Configuration agent provides ConfigMap and Secret management")
    void testKubernetesConfigManagement() {
        ConfigurationContext context = new ConfigurationContext();
        context.setServiceName("pos-inventory");
        context.setConfigurationType("application-config");
        context.setPlatform("kubernetes");
        
        String guidance = configAgent.provideKubernetesConfigGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("ConfigMap"));
        assertTrue(guidance.contains("Secret"));
        assertTrue(guidance.contains("environment variables"));
        assertTrue(guidance.contains("volume mount"));
    }

    @Test
    @DisplayName("Resilience agent provides Kubernetes health checks and probes")
    void testKubernetesHealthChecks() {
        ResilienceContext context = new ResilienceContext();
        context.setServiceName("pos-customer");
        context.setPlatform("kubernetes");
        context.setFailureType("application");
        
        String guidance = resilienceAgent.provideKubernetesHealthCheckGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("liveness probe"));
        assertTrue(guidance.contains("readiness probe"));
        assertTrue(guidance.contains("startup probe"));
        assertTrue(guidance.contains("health check"));
    }

    @Test
    @DisplayName("CI/CD agent provides Helm chart deployment guidance")
    void testHelmChartDeployment() {
        CICDContext context = new CICDContext();
        context.setServiceName("pos-order");
        context.setDeploymentTarget("kubernetes");
        context.setPackagingTool("helm");
        
        String guidance = cicdAgent.provideHelmDeploymentGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("Helm"));
        assertTrue(guidance.contains("chart"));
        assertTrue(guidance.contains("values.yaml"));
        assertTrue(guidance.contains("template"));
    }

    @Test
    @DisplayName("Resilience agent provides pod disruption budget guidance")
    void testPodDisruptionBudget() {
        ResilienceContext context = new ResilienceContext();
        context.setServiceName("pos-catalog");
        context.setPlatform("kubernetes");
        context.setFailureType("node");
        
        String guidance = resilienceAgent.providePodDisruptionBudgetGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("PodDisruptionBudget"));
        assertTrue(guidance.contains("minAvailable"));
        assertTrue(guidance.contains("maxUnavailable"));
        assertTrue(guidance.contains("disruption"));
    }

    @Test
    @DisplayName("Configuration agent provides service mesh configuration")
    void testServiceMeshConfiguration() {
        ConfigurationContext context = new ConfigurationContext();
        context.setServiceName("pos-vehicle-inventory");
        context.setConfigurationType("service-mesh");
        context.setPlatform("kubernetes");
        context.setServiceMesh("istio");
        
        String guidance = configAgent.provideServiceMeshGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("Istio"));
        assertTrue(guidance.contains("sidecar"));
        assertTrue(guidance.contains("traffic management"));
        assertTrue(guidance.contains("security policy"));
    }

    @Test
    @DisplayName("CI/CD agent provides canary deployment with Kubernetes")
    void testCanaryDeploymentKubernetes() {
        CICDContext context = new CICDContext();
        context.setServiceName("pos-price");
        context.setDeploymentTarget("kubernetes");
        context.setDeploymentStrategy("canary");
        
        String guidance = cicdAgent.provideCanaryDeploymentGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("canary"));
        assertTrue(guidance.contains("traffic splitting"));
        assertTrue(guidance.contains("monitoring"));
        assertTrue(guidance.contains("rollback"));
    }

    @Test
    @DisplayName("Resilience agent provides horizontal pod autoscaling")
    void testHorizontalPodAutoscaling() {
        ResilienceContext context = new ResilienceContext();
        context.setServiceName("pos-work-order");
        context.setPlatform("kubernetes");
        context.setScalingType("horizontal");
        
        String guidance = resilienceAgent.provideHorizontalPodAutoscalerGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("HorizontalPodAutoscaler"));
        assertTrue(guidance.contains("CPU"));
        assertTrue(guidance.contains("memory"));
        assertTrue(guidance.contains("scaling"));
    }

    @Test
    @DisplayName("Complete Kubernetes microservice deployment scenario")
    void testCompleteKubernetesMicroserviceDeployment() {
        String serviceName = "pos-shop-manager";
        
        // CI/CD deployment strategy
        CICDContext cicdContext = new CICDContext();
        cicdContext.setServiceName(serviceName);
        cicdContext.setDeploymentTarget("kubernetes");
        cicdContext.setDeploymentStrategy("blue-green");
        String deploymentGuidance = cicdAgent.provideKubernetesDeploymentGuidance(cicdContext);
        
        // Configuration management
        ConfigurationContext configContext = new ConfigurationContext();
        configContext.setServiceName(serviceName);
        configContext.setConfigurationType("application-config");
        configContext.setPlatform("kubernetes");
        String configGuidance = configAgent.provideKubernetesConfigGuidance(configContext);
        
        // Resilience patterns
        ResilienceContext resilienceContext = new ResilienceContext();
        resilienceContext.setServiceName(serviceName);
        resilienceContext.setPlatform("kubernetes");
        resilienceContext.setFailureType("application");
        String resilienceGuidance = resilienceAgent.provideKubernetesHealthCheckGuidance(resilienceContext);
        
        // Verify comprehensive Kubernetes guidance
        assertNotNull(deploymentGuidance);
        assertNotNull(configGuidance);
        assertNotNull(resilienceGuidance);
        
        assertTrue(deploymentGuidance.contains("blue-green"));
        assertTrue(configGuidance.contains("ConfigMap"));
        assertTrue(resilienceGuidance.contains("probe"));
        
        // Verify service-specific content
        assertTrue(deploymentGuidance.contains(serviceName));
        assertTrue(configGuidance.contains(serviceName));
        assertTrue(resilienceGuidance.contains("health check"));
    }

    @Test
    @DisplayName("Multi-environment Kubernetes deployment guidance")
    void testMultiEnvironmentKubernetesDeployment() {
        String serviceName = "pos-accounting";
        
        // Development environment
        CICDContext devContext = new CICDContext();
        devContext.setServiceName(serviceName);
        devContext.setDeploymentTarget("kubernetes");
        devContext.setEnvironment("development");
        String devGuidance = cicdAgent.provideKubernetesDeploymentGuidance(devContext);
        
        // Production environment
        CICDContext prodContext = new CICDContext();
        prodContext.setServiceName(serviceName);
        prodContext.setDeploymentTarget("kubernetes");
        prodContext.setEnvironment("production");
        String prodGuidance = cicdAgent.provideKubernetesDeploymentGuidance(prodContext);
        
        assertNotNull(devGuidance);
        assertNotNull(prodGuidance);
        
        // Verify environment-specific considerations
        assertTrue(devGuidance.contains("development"));
        assertTrue(prodGuidance.contains("production"));
        
        // Production should have more stringent requirements
        assertTrue(prodGuidance.contains("resource limits") || 
                  prodGuidance.contains("security") || 
                  prodGuidance.contains("monitoring"));
    }
}
