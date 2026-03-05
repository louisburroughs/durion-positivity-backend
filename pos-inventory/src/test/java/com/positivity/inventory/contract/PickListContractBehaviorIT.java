package com.positivity.inventory.contract;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.picklist.CreatePickListRequest;
import com.positivity.inventory.internal.dto.picklist.PickListResponse;
import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.inventory.service.PickListService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior integration tests for the Pick List API — Story #28:
 * Create Pick List / Pick Tasks for Workorder.
 *
 * <p>
 * All HTTP endpoint tests are intentionally RED: {@code PickListController}
 * does not yet exist, so all requests return 404. Tests expecting 201/200 will
 * fail with status mismatch — this is the expected RED evidence.
 *
 * <p>
 * ADR compliance:
 * <ul>
 * <li>ADR-0011: gateway auth headers (X-User, X-Authorities) required</li>
 * <li>ADR-0014: internal service security — all endpoints require gateway
 * auth</li>
 * <li>ADR-0017: HTTP response codes: 201 Created, 200 OK, 204 No Content,
 * 403</li>
 * <li>ADR-0023: no tenantId in request or response</li>
 * </ul>
 *
 * Issue: #28
 */
@DisplayName("PickList Contract Behavior — Story #28")
class PickListContractBehaviorIT extends BaseContractIntegrationTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);


        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private PickListService pickListService;

        // ─── CH1: POST /v1/inventory/pick-lists — 201 Created ───────────────────────

        /**
         * CH1: Verifies that POST /v1/inventory/pick-lists with a valid request body
         * and gateway auth returns 201 Created with pickListId and status=DRAFT.
         *
         * <p>
         * RED: {@code PickListController} does not exist — request returns 404.
         *
         * Issue: #28
         */
        @Test
        @DisplayName("POST /v1/inventory/pick-lists with valid body + gateway auth → 201 Created")
        void CH1_createPickList_validBodyAndAuth_returns201() throws Exception {
                // Issue #28: CH1 — create endpoint must return 201 with pickListId and
                // status=DRAFT
                UUID workorderId = UUID.randomUUID();
                UUID pickListId = UUID.randomUUID();

                PickListResponse mockResponse = new PickListResponse(
                                pickListId,
                                workorderId,
                                PickListStatus.DRAFT,
                                1,
                                Instant.now(TEST_CLOCK).plusSeconds(3600),
                                Instant.now(TEST_CLOCK),
                                Instant.now(TEST_CLOCK));

                when(pickListService.createPickList(any(CreatePickListRequest.class)))
                                .thenReturn(mockResponse);

                String requestBody = objectMapper.writeValueAsString(
                                new CreatePickListRequest(workorderId, Instant.now(TEST_CLOCK).plusSeconds(3600), 1,
                                                UUID.randomUUID()));

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/pick-lists"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.pickListId").value(pickListId.toString()))
                                .andExpect(jsonPath("$.workorderId").value(workorderId.toString()))
                                .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        // ─── CH2: POST /v1/inventory/pick-lists — 403 without gateway auth ──────────

        /**
         * CH2: Verifies that POST /v1/inventory/pick-lists without the required
         * X-Authorities header returns 403 Forbidden per ADR-0011 and ADR-0014.
         *
         * Issue: #28
         */
        @Test
        @DisplayName("POST /v1/inventory/pick-lists without gateway auth → 403 Forbidden")
        void CH2_createPickList_missingGatewayAuth_returns403() throws Exception {
                // Issue #28: ADR-0011/ADR-0014 — missing X-Authorities header must yield 403
                UUID workorderId = UUID.randomUUID();
                String requestBody = objectMapper.writeValueAsString(
                                new CreatePickListRequest(workorderId, null, 0, null));

                mockMvc.perform(post("/v1/inventory/pick-lists")
                                .header("X-User", "test-user")
                                // Deliberately omit X-Authorities to trigger 403
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isForbidden());
        }

        // ─── CH3: GET /v1/inventory/pick-lists/{id} — 200 OK ────────────────────────

        /**
         * CH3: Verifies that GET /v1/inventory/pick-lists/{id} with a valid ID and
         * gateway auth returns 200 OK with the pick list JSON.
         *
         * <p>
         * RED: {@code PickListController} does not exist — request returns 404.
         *
         * Issue: #28
         */
        @Test
        @DisplayName("GET /v1/inventory/pick-lists/{id} with valid ID + gateway auth → 200 OK")
        void CH3_getPickList_validId_returns200() throws Exception {
                // Issue #28: CH3 — get endpoint must return 200 with full pick list response
                UUID pickListId = UUID.randomUUID();
                UUID workorderId = UUID.randomUUID();

                PickListResponse mockResponse = new PickListResponse(
                                pickListId,
                                workorderId,
                                PickListStatus.DRAFT,
                                0,
                                null,
                                Instant.now(TEST_CLOCK),
                                Instant.now(TEST_CLOCK));

                when(pickListService.getPickList(eq(pickListId))).thenReturn(mockResponse);

                mockMvc.perform(withGatewayAuth(get("/v1/inventory/pick-lists/{id}", pickListId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.pickListId").value(pickListId.toString()))
                                .andExpect(jsonPath("$.workorderId").value(workorderId.toString()))
                                .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        // ─── CH4: GET /v1/inventory/pick-lists?workorderId=... — 200 OK ─────────────

        /**
         * CH4: Verifies that GET /v1/inventory/pick-lists?workorderId={id} with a
         * valid workorderId returns 200 OK with a JSON array.
         *
         * <p>
         * RED: {@code PickListController} does not exist — request returns 404.
         *
         * Issue: #28
         */
        @Test
        @DisplayName("GET /v1/inventory/pick-lists?workorderId=... with valid workorderId → 200 OK with array")
        void CH4_getPickListsForWorkorder_validWorkorderId_returns200WithArray() throws Exception {
                // Issue #28: CH4 — list endpoint must return 200 with JSON array
                UUID workorderId = UUID.randomUUID();
                UUID pickListId = UUID.randomUUID();

                PickListResponse mockEntry = new PickListResponse(
                                pickListId,
                                workorderId,
                                PickListStatus.DRAFT,
                                0,
                                null,
                                Instant.now(TEST_CLOCK),
                                Instant.now(TEST_CLOCK));

                when(pickListService.getPickListsForWorkorder(eq(workorderId))).thenReturn(List.of(mockEntry));

                mockMvc.perform(withGatewayAuth(get("/v1/inventory/pick-lists")
                                .param("workorderId", workorderId.toString())))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].pickListId").value(pickListId.toString()))
                                .andExpect(jsonPath("$[0].workorderId").value(workorderId.toString()));
        }

        // ─── CH5: PATCH /v1/inventory/pick-lists/{id}/status — 200 OK ───────────────

        /**
         * CH5: Verifies that PATCH /v1/inventory/pick-lists/{id}/status with
         * status=READY_TO_PICK returns 200 OK with updated status.
         *
         * <p>
         * Note: The story describes the target state as "RELEASED"; the nearest enum
         * value is {@link PickListStatus#READY_TO_PICK}.
         *
         * <p>
         * RED: {@code PickListController} does not exist — request returns 404.
         *
         * Issue: #28
         */
        @Test
        @DisplayName("PATCH /v1/inventory/pick-lists/{id}/status with status=READY_TO_PICK → 200 OK")
        void CH5_updatePickListStatus_readyToPick_returns200() throws Exception {
                // Issue #28: CH5 — status update endpoint must return 200 with updated status
                UUID pickListId = UUID.randomUUID();
                UUID workorderId = UUID.randomUUID();

                PickListResponse mockResponse = new PickListResponse(
                                pickListId,
                                workorderId,
                                PickListStatus.READY_TO_PICK,
                                0,
                                null,
                                Instant.now(TEST_CLOCK),
                                Instant.now(TEST_CLOCK));

                when(pickListService.updatePickListStatus(eq(pickListId), eq(PickListStatus.READY_TO_PICK)))
                                .thenReturn(mockResponse);

                String requestBody = objectMapper.writeValueAsString(Map.of("status", "READY_TO_PICK"));

                mockMvc.perform(withGatewayAuth(patch("/v1/inventory/pick-lists/{id}/status", pickListId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.pickListId").value(pickListId.toString()))
                                .andExpect(jsonPath("$.status").value("READY_TO_PICK"));
        }
}
