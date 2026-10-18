package com.positivity.extractionworker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The documentation-only spec and the gateway-chain rules the worker keeps for actuator and docs. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void specNamesBothOperationsWithTheAdr0042LeadIns() throws Exception {
        String spec = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(spec)
                .contains("/extraction-worker/v1/inspections")
                .contains("/extraction-worker/v1/renderings")
                .contains("workerSecret")
                .contains("Use this tool")
                .contains("Preconditions:")
                .contains("Required inputs:")
                .contains("PAGE_NOT_RENDERABLE");
    }

    @Test
    void actuatorKeepsTheGatewayChainRules() throws Exception {
        // The management port is separate, so MockMvc has no actuator handler: what matters here is that the
        // security chain answers before dispatch. Health is open, every other endpoint needs credentials.
        int health = mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
        assertThat(health).isNotIn(401, 403);
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void theApiPathHasNoAnonymousAccess() throws Exception {
        mvc.perform(get("/extraction-worker/v1/inspections")).andExpect(status().isUnauthorized());
    }
}
