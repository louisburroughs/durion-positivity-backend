package com.positivity.catalog.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseContractIntegrationTest;
import com.positivity.catalog.internal.dto.CatalogItemRequestDto;
import com.positivity.catalog.internal.service.CatalogServiceImpl;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@DisplayName("MSRP Contract Behavioral Tests")
class MsrpContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private CatalogServiceImpl catalogService;

        @Test
        @DisplayName("CP-167-001: Create MSRP and read active MSRP")
        void createMsrpAndGetActive() throws Exception {
                UUID productId = createProductAndReturnId("CAP167 MSRP Product A");

                mockMvc.perform(withAuth(post("/v1/products/{productId}/msrp", productId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "amount", "199.9900",
                                                "currency", "USD",
                                                "effectiveStartDate", LocalDate.now().minusDays(1).toString(),
                                                "createdByUserId", UUID.randomUUID().toString()))))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.productId").value(productId.toString()))
                                .andExpect(jsonPath("$.amount").value("199.9900"));

                mockMvc.perform(withAuth(get("/v1/products/{productId}/msrp/active", productId)
                                .param("asOf", LocalDate.now().toString())))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.productId").value(productId.toString()))
                                .andExpect(jsonPath("$.amount").value("199.9900"));
        }

        @Test
        @DisplayName("VE-167-001: Reject MSRP where effective end date is before start date")
        void rejectInvalidDateWindow() throws Exception {
                UUID productId = createProductAndReturnId("CAP167 MSRP Product B");

                mockMvc.perform(withAuth(post("/v1/products/{productId}/msrp", productId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "amount", "149.9900",
                                                "currency", "USD",
                                                "effectiveStartDate", LocalDate.now().toString(),
                                                "effectiveEndDate", LocalDate.now().minusDays(1).toString(),
                                                "createdByUserId", UUID.randomUUID().toString()))))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("VE-167-002: Reject overlapping MSRP windows for same product")
        void rejectOverlappingMsrpRanges() throws Exception {
                UUID productId = createProductAndReturnId("CAP167 MSRP Product C");

                mockMvc.perform(withAuth(post("/v1/products/{productId}/msrp", productId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "amount", "99.9900",
                                                "currency", "USD",
                                                "effectiveStartDate", LocalDate.now().minusDays(2).toString(),
                                                "effectiveEndDate", LocalDate.now().plusDays(2).toString(),
                                                "createdByUserId", UUID.randomUUID().toString()))))
                                .andExpect(status().isCreated());

                mockMvc.perform(withAuth(post("/v1/products/{productId}/msrp", productId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "amount", "109.9900",
                                                "currency", "USD",
                                                "effectiveStartDate", LocalDate.now().plusDays(1).toString(),
                                                "effectiveEndDate", LocalDate.now().plusDays(4).toString(),
                                                "createdByUserId", UUID.randomUUID().toString()))))
                                .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("LC-167-001: Update MSRP amount and return updated active value")
        void updateMsrpAndVerifyActiveValue() throws Exception {
                UUID productId = createProductAndReturnId("CAP167 MSRP Product D");

                MvcResult createResult = mockMvc.perform(withAuth(post("/v1/products/{productId}/msrp", productId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "amount", "79.9900",
                                                "currency", "USD",
                                                "effectiveStartDate", LocalDate.now().minusDays(1).toString(),
                                                "effectiveEndDate", LocalDate.now().plusDays(10).toString(),
                                                "createdByUserId", UUID.randomUUID().toString()))))
                                .andExpect(status().isCreated())
                                .andReturn();

                Map<String, Object> created = objectMapper.readValue(createResult.getResponse().getContentAsString(),
                                Map.class);
                String msrpId = String.valueOf(created.get("msrpId"));

                mockMvc.perform(withAuth(put("/v1/products/{productId}/msrp/{msrpId}", productId, msrpId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "amount", "84.9900",
                                                "currency", "USD",
                                                "effectiveStartDate", LocalDate.now().minusDays(1).toString(),
                                                "effectiveEndDate", LocalDate.now().plusDays(10).toString(),
                                                "createdByUserId", UUID.randomUUID().toString()))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.amount").value("84.9900"));

                mockMvc.perform(withAuth(get("/v1/products/{productId}/msrp/active", productId)
                                .param("asOf", LocalDate.now().toString())))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.amount").value("84.9900"));
        }

        private UUID createProductAndReturnId(String name) {
                CatalogItemRequestDto request = new CatalogItemRequestDto();
                request.setName(name);
                request.setShortDescription("Short " + name);
                request.setLongDescription("Long " + name);
                request.setType("PHYSICAL");
                return catalogService.addCatalogItem("product", request).getId();
        }
}
