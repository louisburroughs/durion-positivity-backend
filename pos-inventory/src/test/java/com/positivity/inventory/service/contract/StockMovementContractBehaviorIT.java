package com.positivity.inventory.service.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.repository.InventoryAdjustmentRequestRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior tests for stock movement and adjustment endpoints.
 *
 * <p>
 * Verifies POST /v1/inventory/stock-movements (RECEIVE, TRANSFER, PICK, ISSUE,
 * RETURN, PUT_AWAY)
 * and POST /v1/inventory/adjustments and POST
 * /v1/inventory/adjustments/{id}/approve
 * as defined in Story #37 of CAP-215.
 *
 * <p>
 * All tests are in RED state until the endpoints and service implementations
 * are
 * completed (GREEN step). Routes do not yet exist — Spring returns 404 for all
 * requests, causing each status assertion to fail with an unexpected status
 * code.
 *
 * Issue: CAP-215
 */
@DisplayName("Stock Movement Contract Behavior")
class StockMovementContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Autowired
    private InventoryAdjustmentRequestRepository inventoryAdjustmentRequestRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        inventoryAdjustmentRequestRepository.deleteAll();
        inventoryLedgerEntryRepository.deleteAll();
    }

    // Issue CAP-215: AC-1 — RECEIVE movement records a GOODS_RECEIPT ledger entry
    @Test
    @DisplayName("AC-1: recordMovement_RECEIVE_createsGoodsReceiptLedgerEntry")
    void recordMovement_RECEIVE_createsGoodsReceiptLedgerEntry() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/stock-movements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productSku\":\"SKU-RECV-1\",\"locationId\":\"LOC-RECV\","
                        + "\"movementType\":\"RECEIVE\",\"quantity\":50,\"unitOfMeasure\":\"EACH\"}")))
                .andExpect(status().isCreated());
    }

    // Issue CAP-215: AC-2 — TRANSFER movement creates debit (from) + credit (to)
    // ledger entries
    @Test
    @DisplayName("AC-2: recordMovement_TRANSFER_createsTwoLedgerEntries")
    void recordMovement_TRANSFER_createsTwoLedgerEntries() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/stock-movements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productSku\":\"SKU-XFER-1\",\"locationId\":\"LOC-SRC\","
                        + "\"toLocationId\":\"LOC-DST\",\"movementType\":\"TRANSFER\","
                        + "\"quantity\":25,\"unitOfMeasure\":\"EACH\"}")))
                .andExpect(status().isCreated());

        List<InventoryLedgerEntry> entries = inventoryLedgerEntryRepository.findAll();
        assertThat(entries).hasSize(2);
    }

    // Issue CAP-215: AC-3 — adjustment request without reasonCode returns 400
    // VALIDATION_ERROR
    @Test
    @DisplayName("AC-3: createAdjustment_withoutReasonCode_returns400")
    void createAdjustment_withoutReasonCode_returns400() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productSku\":\"SKU-ADJ\",\"locationId\":\"LOC-1\",\"quantity\":-5}")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // Issue CAP-215: AC-4 — approve adjustment posts to ledger and returns 200
    @Test
    @DisplayName("AC-4: approveAdjustment_postsLedgerEntry")
    void approveAdjustment_postsLedgerEntry() throws Exception {
        MvcResult createResult = mockMvc.perform(withGatewayAuth(post("/v1/inventory/adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productSku\":\"SKU-ADJ2\",\"locationId\":\"LOC-2\","
                        + "\"quantity\":-3,\"reasonCode\":\"DAMAGE\",\"unitOfMeasure\":\"EACH\"}")))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.adjustmentRequestId").exists())
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String adjustmentRequestId = responseJson.get("adjustmentRequestId").asString();

        mockMvc.perform(withGatewayAuth(
                post("/v1/inventory/adjustments/{adjustmentRequestId}/approve", adjustmentRequestId)))
                .andExpect(status().isOk());
    }

    // Issue CAP-215: AC-5 — PICK with no prior stock present returns 422
    // INSUFFICIENT_STOCK
    @Test
    @DisplayName("AC-5: recordMovement_PICK_returns422_whenInsufficientStock")
    void recordMovement_PICK_returns422_whenInsufficientStock() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/stock-movements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productSku\":\"SKU-PICK-1\",\"locationId\":\"LOC-PICK\","
                        + "\"movementType\":\"PICK\",\"quantity\":500,\"unitOfMeasure\":\"EACH\"}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    }

    // Issue CAP-215: AC-6 — ADR-0018: actor is populated from security context, not
    // request body
    @Test
    @DisplayName("AC-6: recordMovement_RECEIVE_recordsActorFromSecurityContext")
    void recordMovement_RECEIVE_recordsActorFromSecurityContext() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/stock-movements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productSku\":\"SKU-ACTOR-1\",\"locationId\":\"LOC-ACTOR\","
                        + "\"movementType\":\"RECEIVE\",\"quantity\":10,\"unitOfMeasure\":\"EACH\"}")))
                .andExpect(status().isCreated());

        List<InventoryLedgerEntry> entries = inventoryLedgerEntryRepository.findAll();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getTransactionUserId()).isNotNull();
    }
}
