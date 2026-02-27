package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.returns.ReturnItemLine;
import com.positivity.inventory.internal.dto.returns.ReturnItemsRequest;
import com.positivity.inventory.internal.dto.returns.ReturnResponse;
import com.positivity.inventory.internal.exception.ReturnQuantityExceededException;
import com.positivity.inventory.service.ReturnService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior integration tests for POST /v1/inventory/returns — Story
 * #177:
 * Return Unused Items to Stock with Reason.
 *
 * <p>
 * RC1 is intentionally RED: {@code ReturnService} is not stubbed in that test;
 * Mockito returns {@code null} by default; the controller wraps null in a
 * 201-Created body, so the {@code $.returnId} assertion fails to find the
 * expected
 * UUID value.
 *
 * <p>
 * RC2, RC3, and RC4 are GREEN-in-RED-phase because the infrastructure is
 * already
 * in place:
 * <ul>
 * <li>RC2: {@code ReturnController} carries {@code @PreAuthorize}</li>
 * <li>RC3: {@code ReturnItemsRequest.returnReason} is {@code @NotBlank} and
 * the controller uses {@code @Valid}</li>
 * <li>RC4: {@code InventoryGlobalExceptionHandler} already maps
 * {@code ReturnQuantityExceededException} → 422</li>
 * </ul>
 *
 * <p>
 * ADR compliance:
 * <ul>
 * <li>ADR-0011: gateway auth headers (X-User, X-Authorities) required</li>
 * <li>ADR-0014: internal service security — all state-changing endpoints
 * require
 * gateway auth</li>
 * <li>ADR-0017: HTTP response codes: 201 Created, 400 Bad Request, 403
 * Forbidden,
 * 422 Unprocessable Entity</li>
 * <li>ADR-0018: transactionUserId sourced from X-User header via security
 * context</li>
 * </ul>
 *
 * Issue: #177
 */
@DisplayName("Return Contract Behavior — Story #177")
class ReturnContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private ReturnService returnService;

        // ─── RC1: POST /v1/inventory/returns — 201 Created with service returnId ─────

        /**
         * RC1: Verifies POST /v1/inventory/returns with a valid request body and
         * gateway
         * auth returns 201 Created, and that the {@code returnId} in the response
         * originates from {@code ReturnService} (not a controller-generated value).
         *
         * <p>
         * RED: {@code returnService} is not stubbed; Mockito returns {@code null} by
         * default; the controller sets a null response body → {@code $.returnId} is
         * absent
         * and the assertion fails.
         *
         * Issue: #177
         */
        @Test
        @DisplayName("POST /v1/inventory/returns with valid body + auth → 201 Created with service returnId")
        void RC1_returnItemsToStock_validBodyAndAuth_returns201WithServiceReturnId() throws Exception {
                // Issue #177: RC1 — controller must delegate to ReturnService and surface its
                // returnId.

                UUID workorderId = UUID.randomUUID();
                UUID expectedReturnId = UUID.fromString("00000000-0000-0000-0000-000000000042");

                ReturnResponse expectedReturnResponse = new ReturnResponse(
                                expectedReturnId,
                                workorderId,
                                "Leftover brake pads",
                                1,
                                Instant.now(),
                                List.of(UUID.randomUUID()));
                when(returnService.returnItemsToStock(any(ReturnItemsRequest.class)))
                                .thenReturn(expectedReturnResponse);

                ReturnItemsRequest requestBody = new ReturnItemsRequest(
                                workorderId,
                                "Leftover brake pads",
                                List.of(new ReturnItemLine(UUID.randomUUID(), 2)));

                // Act + Assert
                mockMvc.perform(withGatewayAuth(post("/v1/inventory/returns"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.returnId").value(expectedReturnId.toString()))
                                .andExpect(jsonPath("$.workorderId").value(workorderId.toString()))
                                .andExpect(jsonPath("$.totalItemsReturned").value(1));
        }

        // ─── RC2: POST /v1/inventory/returns — 403 without gateway auth ──────────────

        /**
         * RC2: Verifies POST /v1/inventory/returns without the required
         * {@code X-Authorities} header returns 403 Forbidden per ADR-0011 and ADR-0014.
         *
         * <p>
         * GREEN in RED phase: {@code ReturnController} already carries
         * {@code @PreAuthorize("hasAnyAuthority('inventory:availability:read','inventory:adjustment:create')")}.
         *
         * Issue: #177
         */
        @Test
        @DisplayName("POST /v1/inventory/returns without gateway auth → 403 Forbidden")
        void RC2_returnItemsToStock_missingGatewayAuth_returns403() throws Exception {
                // Issue #177: ADR-0011/ADR-0014 — omitting X-Authorities must yield 403
                ReturnItemsRequest requestBody = new ReturnItemsRequest(
                                UUID.randomUUID(),
                                "Surplus parts",
                                List.of(new ReturnItemLine(UUID.randomUUID(), 1)));

                mockMvc.perform(post("/v1/inventory/returns")
                                .header("X-User", "test-user")
                                // Deliberately omit X-Authorities to trigger 403
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().isForbidden());
        }

        // ─── RC3: POST /v1/inventory/returns — 400 for blank returnReason ────────────

        /**
         * RC3: Verifies POST /v1/inventory/returns returns 400 Bad Request when
         * {@code returnReason} is blank.
         *
         * <p>
         * GREEN in RED phase: {@code ReturnItemsRequest.returnReason} is annotated
         * {@code @NotBlank} and the controller uses {@code @Valid @RequestBody}.
         *
         * Issue: #177
         */
        @Test
        @DisplayName("POST /v1/inventory/returns with blank returnReason → 400 Bad Request")
        void RC3_returnItemsToStock_blankReturnReason_returns400() throws Exception {
                // Issue #177: RC3 — blank reason must be rejected by @NotBlank bean validation
                ReturnItemsRequest requestBody = new ReturnItemsRequest(
                                UUID.randomUUID(),
                                "   ", // blank — violates @NotBlank
                                List.of(new ReturnItemLine(UUID.randomUUID(), 1)));

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/returns"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().isBadRequest());
        }

        // ─── RC4: POST /v1/inventory/returns — 422 for quantity exceeded ─────────────

        /**
         * RC4: Verifies POST /v1/inventory/returns returns 422 Unprocessable Entity
         * when
         * the service throws {@link ReturnQuantityExceededException}.
         *
         * <p>
         * GREEN in RED phase: {@code InventoryGlobalExceptionHandler} already maps
         * {@code ReturnQuantityExceededException} → 422 UNPROCESSABLE_ENTITY.
         *
         * Issue: #177
         */
        @Test
        @DisplayName("POST /v1/inventory/returns with quantity exceeded → 422 Unprocessable Entity")
        void RC4_returnItemsToStock_quantityExceeded_returns422() throws Exception {
                // Issue #177: RC4 — ReturnQuantityExceededException must map to 422 via handler
                UUID skuId = UUID.randomUUID();
                when(returnService.returnItemsToStock(any(ReturnItemsRequest.class)))
                                .thenThrow(new ReturnQuantityExceededException(skuId, 99, 2));

                ReturnItemsRequest requestBody = new ReturnItemsRequest(
                                UUID.randomUUID(),
                                "Overclaim return",
                                List.of(new ReturnItemLine(skuId, 99)));

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/returns"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().isUnprocessableEntity());
        }
}
