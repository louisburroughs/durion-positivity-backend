package com.positivity.catalog.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@DisplayName("UOM Conversion Contract Behavioral Tests")
class UomConversionContractBehaviorIT extends BaseIntegrationTest {

    @Test
    @DisplayName("CP-165-020: Create valid UOM conversion returns 201")
    void createConversion_returnsCreated() throws Exception {
        mockMvc.perform(withAuth(post("/v1/products/uom-conversions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "fromUomCode", "EA",
                        "toUomCode", "BOX",
                        "conversionFactor", 10.0)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromUomCode").value("EA"))
                .andExpect(jsonPath("$.toUomCode").value("BOX"));
    }

    @Test
    @DisplayName("VE-165-020: Duplicate conversion pair returns 409")
    void duplicatePair_returnsConflict() throws Exception {
        Map<String, Object> payload = Map.of(
                "fromUomCode", "L",
                "toUomCode", "ML",
                "conversionFactor", 1000.0);

        mockMvc.perform(withAuth(post("/v1/products/uom-conversions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(post("/v1/products/uom-conversions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("VE-165-021: Zero conversion factor returns 400")
    void zeroFactor_returnsBadRequest() throws Exception {
        mockMvc.perform(withAuth(post("/v1/products/uom-conversions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "fromUomCode", "EA",
                        "toUomCode", "EA",
                        "conversionFactor", 0.0)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("CP-165-021: Deactivate conversion returns 204")
    void deactivateConversion_returnsNoContent() throws Exception {
        MvcResult createResult = mockMvc.perform(withAuth(post("/v1/products/uom-conversions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "fromUomCode", "GAL",
                        "toUomCode", "QT",
                        "conversionFactor", 4.0)))))
                .andExpect(status().isCreated())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(createResult.getResponse().getContentAsString(),
                Map.class);
        UUID id = UUID.fromString((String) response.get("id"));

        mockMvc.perform(withAuth(delete("/v1/products/uom-conversions/{id}", id)))
                .andExpect(status().isNoContent());
    }
}
