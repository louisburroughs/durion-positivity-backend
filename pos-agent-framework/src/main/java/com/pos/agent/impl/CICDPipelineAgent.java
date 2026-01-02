package com.pos.agent.impl;

import com.pos.agent.context.AgentContext;
import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.context.CICDContext;
import com.pos.agent.framework.model.AgentType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CICDPipelineAgent - provides CI/CD pipeline guidance and recommendations
 * Extends AbstractAgent for common validation and failure handling
 */
public class CICDPipelineAgent extends AbstractAgent {

        protected static final Map<String, AgentContext> CONTEXT_MAP = new ConcurrentHashMap<>();

        public CICDPipelineAgent() {
                super(AgentType.CICD_PIPELINE, List.of(
                                "pipeline-design",
                                "automated-testing",
                                "deployment-automation",
                                "security-scanning",
                                "artifact-management"));
        }

        @Override
        public AgentContext getOrCreateContext(String sessionId) {
                return CONTEXT_MAP.computeIfAbsent(sessionId,
                                sid -> CICDContext.builder().requestId(sessionId).build());
        }

        @Override
        protected AgentResponse doProcessRequest(AgentRequest request) {
                return AgentResponse.builder()
                                .status(AgentProcessingState.SUCCESS)
                                .output("CI/CD pipeline guidance: " + request.getAgentContext().getDescription())
                                .confidence(0.8)
                                .success(true)
                                .recommendations(List.of("implement pattern", "configure system", "add monitoring"))
                                .build();
        }

        /**
         * Provides Kubernetes deployment guidance based on the given context.
         */
        public AgentResponse provideKubernetesDeploymentGuidance(CICDContext context) {
                return AgentResponse.builder()
                                .status(AgentProcessingState.SUCCESS)
                                .output("Kubernetes deployment guidance for service: " + context.getServiceName())
                                .confidence(0.85)
                                .success(true)
                                .recommendations(List.of(
                                                "Use rolling deployment strategy for zero-downtime updates",
                                                "Configure resource requests and limits",
                                                "Set up liveness and readiness probes"))
                                .build();
        }

        /**
         * Provides Helm deployment guidance based on the given context.
         */
        public AgentResponse provideHelmDeploymentGuidance(CICDContext context) {
                return AgentResponse.builder()
                                .status(AgentProcessingState.SUCCESS)
                                .output("Helm deployment guidance for service: " + context.getServiceName())
                                .confidence(0.85)
                                .success(true)
                                .recommendations(List.of(
                                                "Use Helm charts for templating Kubernetes manifests",
                                                "Version your Helm charts with semantic versioning",
                                                "Store charts in a Helm repository"))
                                .build();
        }

        /**
         * Provides canary deployment guidance based on the given context.
         */
        public AgentResponse provideCanaryDeploymentGuidance(CICDContext context) {
                return AgentResponse.builder()
                                .status(AgentProcessingState.SUCCESS)
                                .output("Canary deployment guidance for service: " + context.getServiceName())
                                .confidence(0.85)
                                .success(true)
                                .recommendations(List.of(
                                                "Deploy new version to small subset of users",
                                                "Monitor metrics and error rates",
                                                "Gradually increase traffic to new version"))
                                .build();
        }

        @Override
        public String generateOutput(String query) {

                return "CI/CD Pipeline Security and Configuration Guidance:\n\n" +
                                "For query: " + query + "\n\n" +
                                "- Implement security scanning in pipeline stages\n" +
                                "- Use artifact verification and signing\n" +
                                "- Apply blue-green deployment strategies\n" +
                                "- Integrate static analysis (SAST) tools\n" +
                                "- Enforce policy checks before deployment\n";
        }

        @Override
        public void updateContext(String sessionId, String guidance) {
                CICDContext ctx = (CICDContext) getOrCreateContext(sessionId);

                if (guidance.contains("maven"))
                        ctx.addBuildTool("maven", Map.of("type", "java-build"));
                if (guidance.contains("docker"))
                        ctx.addBuildTool("docker", Map.of("type", "containerization"));
                if (guidance.contains("blue-green"))
                        ctx.addDeploymentStrategy("blue-green", Map.of("type", "zero-downtime"));
                if (guidance.contains("sast"))
                        ctx.addSecurityScanner("sast", Map.of("type", "static-analysis"));
                if (guidance.contains("dast"))
                        ctx.addSecurityScanner("dast", Map.of("type", "dynamic-analysis"));
                if (guidance.contains("jenkins"))
                        ctx.addOrchestrationTool("jenkins", Map.of("type", "ci-server"));
        }

        @Override
    public void removeContext(String sessionId) {
        CONTEXT_MAP.remove(sessionId);
    }
}
