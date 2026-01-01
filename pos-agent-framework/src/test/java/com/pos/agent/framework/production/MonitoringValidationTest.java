package com.pos.agent.framework.production;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import com.pos.agent.core.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Normalized monitoring validation using core API.
 */
class MonitoringValidationTest {

        private final AgentManager agentManager = new AgentManager();
        private final SecurityContext security = SecurityContext.builder()
                        .jwtToken("valid.jwt.token")
                        .userId("monitor-user")
                        .roles(List.of("monitor"))
                        .permissions(List.of("AGENT_READ", "read"))
                        .serviceId("pos-monitoring-suite")
                        .serviceType("manual")
                        .build();

        @Test
        @DisplayName("Monitoring readiness request processes successfully")
        void processesMonitoringReadinessRequest() {
                AgentContext context = AgentContext.builder()
                                .domain("observability")
                                .property("check", "metrics")
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("monitoring-validation")
                                .description("Monitoring validation test for metrics check")
                                .context(context)
                                .securityContext(security)
                                .requireTLS13(true)
                                .build();

                AgentResponse response = agentManager.processRequest(request);

                assertNotNull(response);
                assertTrue(response.isSuccess());
                assertNotNull(response.getStatus());
                assertTrue(response.getProcessingTimeMs() >= 0);
        }
}
