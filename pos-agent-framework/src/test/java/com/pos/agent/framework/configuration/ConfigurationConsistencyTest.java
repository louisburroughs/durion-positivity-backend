package com.pos.agent.framework.configuration;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import com.pos.agent.core.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationConsistencyTest {

    private final AgentManager agentManager = new AgentManager();
    private final SecurityContext security = SecurityContext.builder()
            .jwtToken("valid.jwt.token")
            .userId("test-user")
            .roles(List.of("tester"))
            .permissions(List.of("read", "execute"))
            .serviceId("pos-testing-suite")
            .serviceType("manual")
            .build();

    @Test
    @DisplayName("Processes configuration guidance request successfully")
    void processesConfigurationRequest() {
        AgentContext context = AgentContext.builder()
                .domain("configuration")
                .property("service", "pos-config")
                .property("scenario", "consistency-check")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("configuration")
                .context(context)
                .securityContext(security)
                .requireTLS13(true)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
        assertTrue(response.getProcessingTimeMs() >= 0);
    }
}
