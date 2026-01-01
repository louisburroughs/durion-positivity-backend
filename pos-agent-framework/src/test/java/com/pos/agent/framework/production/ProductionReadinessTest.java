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
 * Normalized production readiness tests using core API.
 * Validates that an operations request processes and returns status.
 */
class ProductionReadinessTest {

        private final AgentManager agentManager = new AgentManager();
        private final SecurityContext security = SecurityContext.builder()
                        .jwtToken("valid.jwt.token")
                        .userId("ops-user")
                        .roles(List.of("ops"))
                        .permissions(List.of("AGENT_READ", "read"))
                        .serviceId("pos-ops-suite")
                        .serviceType("manual")
                        .build();

        @Test
        @DisplayName("Operations readiness request processes successfully")
        void processesOperationsReadinessRequest() {
                AgentContext context = AgentContext.builder()
                                .domain("operations")
                                .property("check", "readiness")
                                .build();

                AgentRequest request = AgentRequest.builder()
                                .type("production-readiness")
                                .description("Production readiness test for operations domain")
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
