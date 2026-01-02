package com.pos.agent;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import com.pos.agent.core.SecurityContext;

/**
 * Manual test to verify testing guidance using core AgentManager APIs.
 */
public class TestingAgentManualTest {

        public static void main(String[] args) {
                AgentManager agentManager = new AgentManager();

                // Shared security context for manual runs
                SecurityContext security = SecurityContext.builder()
                                .jwtToken("manual-test-token")
                                .userId("manual-tester")
                                .roles(java.util.List.of("admin", "developer", "tester", "operator"))
                                .permissions(java.util.List.of(
                                                "read",
                                                "execute",
                                                "AGENT_READ",
                                                "AGENT_WRITE",
                                                "agent:read",
                                                "agent:write",
                                                "agent:execute"))
                                .serviceId("pos-testing-suite")
                                .serviceType("manual")
                                .build();

                // TDD request via core APIs
                AgentContext tddCtx = AgentContext.builder()
                                .agentDomain("testing")
                                .property("service", "pos-inventory")
                                .property("topic", "tdd")
                                .build();

                AgentRequest tddReq = AgentRequest.builder()
                                .description("TDD guidance manual test")
                                .type("testing")
                                .context(tddCtx)
                                .securityContext(security)
                                .requireTLS13(true)
                                .build();

                AgentResponse tddResp = agentManager.processRequest(tddReq);
                System.out.println("TDD Guidance Test:");
                System.out.println("Success: " + tddResp.isSuccess());
                System.out.println("Status: " + tddResp.getStatus());
                System.out.println("Processing Time (ms): " + tddResp.getProcessingTimeMs());
                System.out.println();

                // Property-based testing request via core APIs
                AgentContext pbtCtx = AgentContext.builder()
                                .agentDomain("testing")
                                .property("service", "pos-price")
                                .property("topic", "property-based-testing")
                                .build();

                AgentRequest pbtReq = AgentRequest.builder()
                                .description("Property-based testing guidance manual test")
                                .type("testing")
                                .context(pbtCtx)
                                .securityContext(security)
                                .requireTLS13(true)
                                .build();

                AgentResponse pbtResp = agentManager.processRequest(pbtReq);
                System.out.println("Property-Based Testing Guidance Test:");
                System.out.println("Success: " + pbtResp.isSuccess());
                System.out.println("Status: " + pbtResp.getStatus());
                System.out.println("Processing Time (ms): " + pbtResp.getProcessingTimeMs());
                System.out.println();

                System.out.println("\n✅ Core AgentManager testing checks completed.");
        }
}