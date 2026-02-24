package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayExecutionResponse;
import com.positivity.inventory.internal.exception.LocationAtCapacityException;
import com.positivity.inventory.internal.exception.LocationNotValidForSkuException;
import com.positivity.inventory.internal.exception.NoOnHandAtSourceLocationException;
import com.positivity.inventory.service.PutawayExecuteService;
import com.positivity.inventory.service.contract.BaseContractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior integration tests for the Putaway Execution API.
 *
 * <p>
 * Verifies the execute put-away move lifecycle (Staging → Storage) per
 * ADR-0011, ADR-0014 (X-User and X-Authorities gateway headers), ADR-0017
 * (HTTP response codes: 200 success, 422 semantic validation failure, 403
 * forbidden), and ADR-0023 (no tenantId in request or response).
 *
 * <p>
 * Tests are intentionally RED: {@code PutawayExecuteController} does not yet
 * exist, so all requests will receive 404 instead of the expected status codes.
 *
 * Issue: #31
 */
@DisplayName("Putaway Execution Contract Behavior")
class PutawayExecutionContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PutawayExecuteService putawayExecuteService;

    // ─── AC1: Successful put-away returns 200 with PutawayExecutionResponse ──

    /**
     * Verifies that executing a valid put-away move returns 200 with a non-null
     * ledgerEntryId, the matching taskId, status COMPLETED, and transactionType
     * PUTAWAY per story #31 AC1.
     *
     * Issue: #31
     */
    @Test
    @DisplayName("AC1: execute putaway with valid request returns 200 with COMPLETED status and PUTAWAY transaction type")
    void executePutaway_validRequest_returns200WithCompletedResponse() throws Exception {
        // Issue #31: successful execution must return ledgerEntryId (non-null),
        // taskId, status=COMPLETED, transactionType=PUTAWAY
        String taskId = UUID.randomUUID().toString();
        String ledgerEntryId = UUID.randomUUID().toString();
        String skuId = UUID.randomUUID().toString();
        String sourceLocationId = UUID.randomUUID().toString();
        String destinationLocationId = UUID.randomUUID().toString();

        PutawayExecutionResponse response = PutawayExecutionResponse.builder()
                .ledgerEntryId(ledgerEntryId)
                .taskId(taskId)
                .skuId(skuId)
                .sourceLocationId(sourceLocationId)
                .destinationLocationId(destinationLocationId)
                .quantityMoved(5)
                .transactionType("PUTAWAY")
                .status("COMPLETED")
                .executedAt(Instant.now().toString())
                .actorId("contract-test-user")
                .build();

        when(putawayExecuteService.executePutaway(eq(taskId), any(PutawayExecutionRequest.class), anyString()))
                .thenReturn(response);

        String requestBody = buildExecutionRequestBody(skuId, sourceLocationId, destinationLocationId, 5);

        mockMvc.perform(withGatewayAuth(post("/api/v1/inventory/putaway/tasks/{taskId}/execute", taskId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerEntryId").value(ledgerEntryId))
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.transactionType").value("PUTAWAY"));
    }

    // ─── AC2: Invalid destination for SKU blocks move → 422 ─────────────────

    /**
     * Verifies that when the destination location is not compatible with the SKU
     * (e.g. temperature class mismatch, zone restriction) the service throws
     * {@link LocationNotValidForSkuException} and the controller returns 422 with
     * error code {@code LOCATION_NOT_VALID_FOR_SKU} per story #31 AC2.
     *
     * <p>
     * No ledger entry is created because the service throws before any
     * persistence occurs.
     *
     * Issue: #31
     */
    @Test
    @DisplayName("AC2: destination invalid for SKU returns 422 with LOCATION_NOT_VALID_FOR_SKU error code")
    void executePutaway_invalidDestinationForSku_returns422WithLocationNotValidErrorCode() throws Exception {
        // Issue #31: LocationNotValidForSkuException → 422 LOCATION_NOT_VALID_FOR_SKU
        // No ledger entry must be created (throws before persistence)
        String taskId = UUID.randomUUID().toString();
        String skuId = UUID.randomUUID().toString();
        String sourceLocationId = UUID.randomUUID().toString();
        String destinationLocationId = UUID.randomUUID().toString();

        when(putawayExecuteService.executePutaway(eq(taskId), any(PutawayExecutionRequest.class), anyString()))
                .thenThrow(new LocationNotValidForSkuException(
                        destinationLocationId, skuId, "temperature class mismatch"));

        String requestBody = buildExecutionRequestBody(skuId, sourceLocationId, destinationLocationId, 3);

        mockMvc.perform(withGatewayAuth(post("/api/v1/inventory/putaway/tasks/{taskId}/execute", taskId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCATION_NOT_VALID_FOR_SKU"));
    }

    // ─── AC3: Destination at full capacity blocks move → 422 ─────────────────

    /**
     * Verifies that when the destination location is at full capacity the service
     * throws {@link LocationAtCapacityException} and the controller returns 422
     * with error code {@code LOCATION_AT_CAPACITY} per story #31 AC3.
     *
     * Issue: #31
     */
    @Test
    @DisplayName("AC3: destination at full capacity returns 422 with LOCATION_AT_CAPACITY error code")
    void executePutaway_destinationAtCapacity_returns422WithLocationAtCapacityErrorCode() throws Exception {
        // Issue #31: LocationAtCapacityException → 422 LOCATION_AT_CAPACITY
        String taskId = UUID.randomUUID().toString();
        String skuId = UUID.randomUUID().toString();
        String sourceLocationId = UUID.randomUUID().toString();
        String destinationLocationId = UUID.randomUUID().toString();

        when(putawayExecuteService.executePutaway(eq(taskId), any(PutawayExecutionRequest.class), anyString()))
                .thenThrow(new LocationAtCapacityException(destinationLocationId, 100, 100));

        String requestBody = buildExecutionRequestBody(skuId, sourceLocationId, destinationLocationId, 4);

        mockMvc.perform(withGatewayAuth(post("/api/v1/inventory/putaway/tasks/{taskId}/execute", taskId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCATION_AT_CAPACITY"));
    }

    // ─── AC4: Source has zero on-hand blocks move → 422 ─────────────────────

    /**
     * Verifies that when the source location shows zero on-hand inventory the
     * service throws {@link NoOnHandAtSourceLocationException} and the controller
     * returns 422 with error code {@code NO_ON_HAND_AT_SOURCE_LOCATION} per story
     * #31 AC4.
     *
     * <p>
     * The system must NOT silently create inventory; reconciliation via cycle
     * count or inventory adjustment is required.
     *
     * Issue: #31
     */
    @Test
    @DisplayName("AC4: source location shows zero on-hand returns 422 with NO_ON_HAND_AT_SOURCE_LOCATION error code")
    void executePutaway_sourceLocationZeroOnHand_returns422WithNoOnHandErrorCode() throws Exception {
        // Issue #31: NoOnHandAtSourceLocationException → 422
        // NO_ON_HAND_AT_SOURCE_LOCATION
        // System must NOT silently create inventory (data consistency error path)
        String taskId = UUID.randomUUID().toString();
        String skuId = UUID.randomUUID().toString();
        String sourceLocationId = UUID.randomUUID().toString();
        String destinationLocationId = UUID.randomUUID().toString();

        when(putawayExecuteService.executePutaway(eq(taskId), any(PutawayExecutionRequest.class), anyString()))
                .thenThrow(new NoOnHandAtSourceLocationException(sourceLocationId, skuId));

        String requestBody = buildExecutionRequestBody(skuId, sourceLocationId, destinationLocationId, 2);

        mockMvc.perform(withGatewayAuth(post("/api/v1/inventory/putaway/tasks/{taskId}/execute", taskId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_ON_HAND_AT_SOURCE_LOCATION"));
    }

    // ─── AC5: Missing X-Authorities header returns 403 ───────────────────────

    /**
     * Verifies that a request without the required inventory authority in the
     * {@code X-Authorities} header returns 403 Forbidden per ADR-0011 and
     * ADR-0014.
     *
     * Issue: #31
     */
    @Test
    @DisplayName("AC5: missing X-Authorities header returns 403 Forbidden")
    void executePutaway_missingAuthority_returns403() throws Exception {
        // Issue #31: ADR-0011/ADR-0014 require authority enforcement;
        // absent X-Authorities header must yield 403 Forbidden
        String taskId = UUID.randomUUID().toString();
        String requestBody = buildExecutionRequestBody(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                1);

        mockMvc.perform(post("/api/v1/inventory/putaway/tasks/{taskId}/execute", taskId)
                .header("X-User", "unauthorized-user")
                // Deliberately omit X-Authorities to trigger 403
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isForbidden());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Builds a JSON request body for executing a putaway move.
     *
     * @param skuId                 the SKU being moved
     * @param sourceLocationId      staging source location ID
     * @param destinationLocationId target storage location ID
     * @param quantity              quantity to move (must be positive)
     * @return serialized JSON request body
     */
    private String buildExecutionRequestBody(
            String skuId,
            String sourceLocationId,
            String destinationLocationId,
            int quantity) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("skuId", skuId);
        node.put("sourceLocationId", sourceLocationId);
        node.put("destinationLocationId", destinationLocationId);
        node.put("quantity", quantity);
        return objectMapper.writeValueAsString(node);
    }
}
