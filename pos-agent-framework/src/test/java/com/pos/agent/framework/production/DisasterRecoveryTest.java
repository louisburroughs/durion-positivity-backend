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
 * Normalized disaster recovery tests using core API.
 */
class DisasterRecoveryTest {

        private final AgentManager agentManager = new AgentManager();
        private final SecurityContext security = SecurityContext.builder()
                        .jwtToken("valid.jwt.token")
                        .userId("dr-user")
                        .roles(List.of("ops"))
                        .permissions(List.of("read"))
                        .serviceId("pos-dr-suite")
                        .serviceType("manual")
                        .build();

        @Test
        @DisplayName("Processes request even under simulated degraded conditions")
        void processesUnderDegradation() {
                AgentContext context = AgentContext.builder()
                                .domain("resilience")
                                .property("mode", "degraded")
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("disaster-recovery")
                                .description("Disaster recovery test under degraded conditions")
                                .context(context)
                                .securityContext(security)
                                .requireTLS13(true)
                                .build();

                AgentResponse response = agentManager.processRequest(request);

                assertNotNull(response);
                assertNotNull(response.getStatus());
                assertTrue(response.getProcessingTimeMs() >= 0);
        }
}
