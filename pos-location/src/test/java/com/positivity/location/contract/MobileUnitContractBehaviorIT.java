package com.positivity.location.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.BaseContractIntegrationTest;
import com.positivity.location.config.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract RED tests for mobile units, service areas, and travel buffer
 * policies.
 *
 * These tests encode Story #76 endpoint contracts and expected HTTP semantics.
 *
 * Issue: #76
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class MobileUnitContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("#76 - POST /v1/mobile-units returns 201")
    void shouldCreateMobileUnit() throws Exception {
        String payload = """
                {
                  "name": "MU-101",
                  "baseLocationId": "018f1f5a-a111-7333-8222-111111111111",
                  "status": "INACTIVE",
                  "notes": "new unit"
                }
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/mobile-units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("#76 - GET /v1/mobile-units returns 200")
    void shouldListMobileUnits() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/mobile-units"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("#76 - GET /v1/mobile-units/{id} returns 404 when missing")
    void shouldReturnNotFoundWhenMobileUnitMissing() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/mobile-units/{id}", "018f1f5a-a222-7333-8222-222222222222")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("#76 - PATCH /v1/mobile-units/{id} returns 200")
    void shouldPatchMobileUnit() throws Exception {
        String patchPayload = """
                {
                  "status": "ACTIVE",
                  "travelBufferPolicyId": "018f1f5a-a333-7333-8222-333333333333"
                }
                """;

        mockMvc.perform(withGatewayAuth(patch("/v1/mobile-units/{id}", "018f1f5a-a222-7333-8222-222222222222")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchPayload)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("#76 - PUT /v1/mobile-units/{id}/coverage-rules returns 404 when mobile unit missing")
    void shouldReturnNotFoundWhenReplacingCoverageRulesForMissingMobileUnit() throws Exception {
        String payload = """
                {
                  "rules": [
                    {
                      "ruleType": "POSTAL_CODE_LIST",
                      "priority": 1,
                      "serviceAreaId": "018f1f5a-a444-7333-8222-444444444444",
                      "validFrom": "2026-01-01",
                      "validTo": null
                    }
                  ]
                }
                """;

        mockMvc.perform(withGatewayAuth(
                        put("/v1/mobile-units/{id}/coverage-rules", "018f1f5a-a222-7333-8222-222222222222")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("#76 - GET /v1/mobile-units/{id}/coverage-rules returns 200")
    void shouldGetCoverageRules() throws Exception {
        mockMvc.perform(withGatewayAuth(
                        get("/v1/mobile-units/{id}/coverage-rules", "018f1f5a-a222-7333-8222-222222222222")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("#76 - POST /v1/service-areas returns 201")
    void shouldCreateServiceArea() throws Exception {
        String payload = """
                {
                  "name": "North Zone",
                  "description": "metro north",
                  "active": true,
                  "postalCodes": [
                    { "postalCode": "98101", "countryCode": "US" }
                  ]
                }
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/service-areas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("#76 - GET /v1/service-areas returns 200")
    void shouldListServiceAreas() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/service-areas"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("#76 - PATCH /v1/service-areas/{id} returns 404 when missing")
    void shouldReturnNotFoundWhenPatchingMissingServiceArea() throws Exception {
        String payload = """
                {
                  "active": false
                }
                """;

        mockMvc.perform(withGatewayAuth(patch("/v1/service-areas/{id}", "018f1f5a-a555-7333-8222-555555555555")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("#76 - PATCH /v1/service-areas/{id} returns 400 for invalid id")
    void shouldReturnBadRequestForInvalidServiceAreaId() throws Exception {
        String payload = """
                {
                  "active": false
                }
                """;

        mockMvc.perform(withGatewayAuth(patch("/v1/service-areas/{id}", "bad-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("#76 - POST /v1/travel-buffer-policies returns 201")
    void shouldCreateTravelBufferPolicy() throws Exception {
        String payload = """
                {
                  "name": "Standard Travel Buffer",
                  "bufferType": "FLAT_MINUTES",
                  "bufferValue": 15,
                  "notes": "default"
                }
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/travel-buffer-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("#76 - GET /v1/travel-buffer-policies returns 200")
    void shouldListTravelBufferPolicies() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/travel-buffer-policies"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("#76 - PATCH /v1/travel-buffer-policies/{id} returns 404 when missing")
    void shouldReturnNotFoundWhenPatchingMissingTravelBufferPolicy() throws Exception {
        String payload = """
                {
                  "bufferValue": 20
                }
                """;

        mockMvc.perform(withGatewayAuth(patch("/v1/travel-buffer-policies/{id}", "018f1f5a-a666-7333-8222-666666666666")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("#76 - PATCH /v1/travel-buffer-policies/{id} returns 400 for invalid id")
    void shouldReturnBadRequestForInvalidTravelBufferPolicyId() throws Exception {
        String payload = """
                {
                  "bufferValue": 20
                }
                """;

        mockMvc.perform(withGatewayAuth(patch("/v1/travel-buffer-policies/{id}", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("#76 - GET /v1/mobile-units:eligible returns 200")
    void shouldReturnEligibleMobileUnits() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/mobile-units:eligible")
                        .param("postalCode", "98101")
                        .param("countryCode", "US")
                        .param("at", "2026-02-22T10:00:00Z")))
                .andExpect(status().isOk());
    }
}
