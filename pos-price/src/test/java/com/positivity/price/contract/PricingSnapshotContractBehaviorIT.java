package com.positivity.price.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.price.BaseContractIntegrationTest;
import com.positivity.price.internal.dto.PricingSnapshotCreateRequest;
import com.positivity.price.service.PricingSnapshotService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contract behavior tests for immutable pricing snapshot read endpoint.
 *
 * Issue: #50
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Pricing Snapshot Contract Behavior")
class PricingSnapshotContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PricingSnapshotService pricingSnapshotService;

    @Test
    @DisplayName("givenExistingSnapshot_whenGetSnapshot_thenReturns200WithSnapshotPayload")
    void givenExistingSnapshot_whenGetSnapshot_thenReturns200WithSnapshotPayload() throws Exception {
        PricingSnapshotCreateRequest request = new PricingSnapshotCreateRequest();
        request.setSourceContext("QUOTE_CHECKOUT");
        request.setItemIdentifier("SKU-ABC-123");
        request.setQuantity(3);
        request.setPrices("{\"unitPrice\":95.00,\"currency\":\"USD\"}");
        request.setAppliedRules("[\"LOCATION_OVERRIDE\",\"CUSTOMER_TIER_DISCOUNT\"]");
        request.setPolicyVersion("policy-v1");

        UUID snapshotId = pricingSnapshotService.createSnapshot(request);

        mockMvc.perform(withGatewayAuth(get("/v1/price/snapshots/{snapshotId}", snapshotId)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.snapshotId").value(snapshotId.toString()))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.sourceContext").value("QUOTE_CHECKOUT"))
                .andExpect(jsonPath("$.itemIdentifier").value("SKU-ABC-123"))
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.prices").value("{\"unitPrice\":95.00,\"currency\":\"USD\"}"))
                .andExpect(jsonPath("$.appliedRules").value("[\"LOCATION_OVERRIDE\",\"CUSTOMER_TIER_DISCOUNT\"]"))
                .andExpect(jsonPath("$.policyVersion").value("policy-v1"));
    }

    @Test
    @DisplayName("givenMissingSnapshot_whenGetSnapshot_thenReturns404")
    void givenMissingSnapshot_whenGetSnapshot_thenReturns404() throws Exception {
        UUID missingSnapshotId = UUID.fromString("55555555-5555-5555-5555-555555555555");

        mockMvc.perform(withGatewayAuth(get("/v1/price/snapshots/{snapshotId}", missingSnapshotId)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Pricing snapshot not found: " + missingSnapshotId));
    }

    @Override
    protected MockHttpServletRequestBuilder withGatewayAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "contract-test-user")
                .header("X-Authorities", "price:snapshots:read")
                .header("X-Correlation-Id", "issue-50-contract-test");
    }
}
