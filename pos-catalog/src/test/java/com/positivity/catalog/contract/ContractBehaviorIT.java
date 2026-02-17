package com.positivity.catalog.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Catalog Backend Contract Behavioral Tests")
class ContractBehaviorIT extends BaseIntegrationTest {

    @Test
    @DisplayName("CP-001: Service health endpoint is reachable")
    void testHealthEndpointReachable() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("VE-001: Unknown product id returns 404")
    void testGetProductById_NotFound() throws Exception {
        mockMvc.perform(withAuth(get("/v1/products/{productId}", UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("VE-002: Unknown catalog id returns 404")
    void testGetCatalogById_NotFound() throws Exception {
        mockMvc.perform(withAuth(get("/v1/products/catalog/{catalogId}", UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }
}
