package com.pos.agent;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import com.pos.agent.core.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PairProgrammingNavigatorDebugTest {

        private final AgentManager agentManager = new AgentManager();
        private final SecurityContext security = SecurityContext.builder()
                        .jwtToken("test-token-12345")
                        .userId("debug-tester")
                        .roles(List.of("tester"))
                        .permissions(List.of("read"))
                        .serviceId("pos-debug-tests")
                        .serviceType("debug")
                        .build();

        @Test
        void debugLoopDetection() {
                String contextDescription = "The service layer keeps getting refactored without progress";

                AgentContext context = AgentContext.builder()
                                .domain("collaboration")
                                .property("scenario", contextDescription)
                                .property("requestType", "loop-detection")
                                .build();

                AgentResponse response = agentManager.processRequest(AgentRequest.builder()
                                .type("collaboration")
                                .context(context)
                                .securityContext(security)
                                .build());

                System.out.println("Context: " + contextDescription);
                System.out.println("Response Success: " + response.isSuccess());
                System.out.println("Response Status: " + response.getStatus());
                System.out.println("Processing Time: " + response.getProcessingTimeMs() + "ms");

                assertTrue(response.isSuccess());
                assertNotNull(response.getStatus());
        }
}