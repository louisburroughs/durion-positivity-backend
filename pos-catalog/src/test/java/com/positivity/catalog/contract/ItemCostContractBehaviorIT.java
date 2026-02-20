package com.positivity.catalog.contract;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseContractIntegrationTest;
import com.positivity.catalog.internal.dto.PurchaseOrderReceivedEventDto;
import com.positivity.catalog.service.ItemCostService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

@DisplayName("Item Cost Contract Behavioral Tests")
class ItemCostContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private ItemCostService itemCostService;

    @Test
    @DisplayName("CP-166-010: Update standard cost with reason -> 200 OK, audit record created")
    void updateStandardCostAndAudit() throws Exception {
        UUID itemId = UUID.randomUUID();

        mockMvc.perform(withAuth(put("/v1/products/items/{itemId}/standard-cost", itemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "newCost", "15.2500",
                        "reasonCode", "INITIAL_STANDARD_COST")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value(itemId.toString()))
                .andExpect(jsonPath("$.standardCost").value(15.2500));

        mockMvc.perform(withAuth(get("/v1/products/items/{itemId}/costs/audit", itemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].costTypeChanged").value("STANDARD"))
                .andExpect(jsonPath("$[0].changeSourceType").value("MANUAL"));
    }

    @Test
    @DisplayName("VE-166-010: Update standard cost without reasonCode -> 400 validation error")
    void updateStandardCostWithoutReasonCode() throws Exception {
        UUID itemId = UUID.randomUUID();

        mockMvc.perform(withAuth(put("/v1/products/items/{itemId}/standard-cost", itemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "newCost", "15.2500",
                        "reasonCode", "")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("CP-166-011: Read item costs -> 200 OK returns all three cost fields")
    void readItemCosts() throws Exception {
        UUID itemId = UUID.randomUUID();

        mockMvc.perform(withAuth(get("/v1/products/items/{itemId}/costs", itemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value(itemId.toString()))
                .andExpect(jsonPath("$.standardCost").value(nullValue()))
                .andExpect(jsonPath("$.lastCost").value(nullValue()))
                .andExpect(jsonPath("$.averageCost").value(nullValue()));
    }

    @Test
    @DisplayName("CP-166-012: Process PO received event -> last and average costs updated per WAC formula")
    void processPoReceivedEventAndVerifyWac() throws Exception {
        UUID itemId = UUID.randomUUID();

        PurchaseOrderReceivedEventDto first = new PurchaseOrderReceivedEventDto();
        first.setItemId(itemId);
        first.setSupplierId(UUID.randomUUID());
        first.setReceivedQty(new BigDecimal("10.0000"));
        first.setReceivedUnitCost(new BigDecimal("5.0000"));
        first.setPurchaseOrderId("PO-166-1");
        itemCostService.processPoReceivedEvent(first);

        PurchaseOrderReceivedEventDto second = new PurchaseOrderReceivedEventDto();
        second.setItemId(itemId);
        second.setSupplierId(UUID.randomUUID());
        second.setReceivedQty(new BigDecimal("5.0000"));
        second.setReceivedUnitCost(new BigDecimal("7.0000"));
        second.setPurchaseOrderId("PO-166-2");
        itemCostService.processPoReceivedEvent(second);

        mockMvc.perform(withAuth(get("/v1/products/items/{itemId}/costs", itemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value(itemId.toString()))
                .andExpect(jsonPath("$.lastCost").value(7.0000))
                .andExpect(jsonPath("$.averageCost").value(5.6667));
    }
}
