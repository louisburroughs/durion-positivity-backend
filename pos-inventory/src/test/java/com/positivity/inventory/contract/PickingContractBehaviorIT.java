package com.positivity.inventory.contract;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.picklist.PickListResponse;
import com.positivity.inventory.internal.dto.picklist.PickTaskResponse;
import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.inventory.internal.exception.PickScanMismatchException;
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
 * Contract behavior integration tests for picking execution endpoints — Story
 * #179:
 * Mechanic Executes Picking (Scan + Confirm).
 *
 * <p>
 * All HTTP endpoint tests are intentionally BLOCKED / RED:
 * the release, confirm, and list-tasks endpoints do not exist in
 * {@code PickListController}, so all requests return 404.
 * Service mock stubs will not compile until scaffold methods are added to
 * {@code PickListService}.
 *
 * <p>
 * ADR compliance:
 * <ul>
 * <li>ADR-0006: pos-inventory provides pick state APIs; workexec owns
 * orchestration</li>
 * <li>ADR-0011: gateway auth headers (X-User, X-Authorities) required</li>
 * <li>ADR-0014: internal service security — all endpoints require gateway
 * auth</li>
 * <li>ADR-0017: 200 OK for mutations returning body, 403 without auth, 422 for
 * domain-policy violations</li>
 * </ul>
 *
 * Issue: #179
 */
@DisplayName("Picking Contract Behavior — Story #179")
class PickingContractBehaviorIT extends BaseContractIntegrationTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);


        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private PickListService pickListService;

        // ─── CH1: POST /v1/inventory/pick-lists/{id}/release — 200 OK ───────────────

        /**
         * CH1: Verifies that POST /v1/inventory/pick-lists/{id}/release with valid
         * gateway auth returns 200 OK with a PickListResponse showing
         * status=READY_TO_PICK.
         *
         * <p>
         * BLOCKED: {@code releasePickList} does not exist on {@code PickListService} —
         * this test will not compile until the scaffold is in place.
         * After scaffold: RED because the controller endpoint does not exist (404).
         *
         * Issue: #179
         */
        @Test
        @DisplayName("POST /v1/inventory/pick-lists/{id}/release with valid auth → 200 READY_TO_PICK")
        void CH1_releasePickList_validAuth_returns200WithReadyToPick() throws Exception {
                // Issue #179: CH1 — release endpoint must return 200 with status=READY_TO_PICK
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

                // BLOCKED: releasePickList does not exist on PickListService
                when(pickListService.releasePickList(eq(pickListId))).thenReturn(mockResponse);

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/pick-lists/{id}/release", pickListId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.pickListId").value(pickListId.toString()))
                                .andExpect(jsonPath("$.status").value("READY_TO_PICK"));
        }

        // ─── CH2: POST /v1/inventory/pick-lists/{id}/release — 403 without auth ─────

        /**
         * CH2: Verifies that POST /v1/inventory/pick-lists/{id}/release without the
         * required X-Authorities header returns 403 Forbidden per ADR-0011 and
         * ADR-0014.
         *
         * Issue: #179
         */
        @Test
        @DisplayName("POST /v1/inventory/pick-lists/{id}/release without gateway auth → 403")
        void CH2_releasePickList_missingGatewayAuth_returns403() throws Exception {
                // Issue #179: ADR-0011/ADR-0014 — missing X-Authorities must yield 403
                UUID pickListId = UUID.randomUUID();

                mockMvc.perform(post("/v1/inventory/pick-lists/{id}/release", pickListId)
                                .header("X-User", "test-user")
                                // Deliberately omit X-Authorities to trigger 403
                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isForbidden());
        }

        // ─── CH3: POST /v1/inventory/pick-lists/{id}/tasks/{taskId}/confirm — 200 OK ─

        /**
         * CH3: Verifies that POST /v1/inventory/pick-lists/{id}/tasks/{taskId}/confirm
         * with a valid scan body and gateway auth returns 200 OK with a
         * PickTaskResponse
         * showing status=PICKED.
         *
         * <p>
         * BLOCKED: {@code confirmPickTask} does not exist on {@code PickListService} —
         * this test will not compile until the scaffold is in place.
         * After scaffold: RED because the controller endpoint does not exist (404).
         *
         * Issue: #179
         */
        @Test
        @DisplayName("POST /v1/inventory/pick-lists/{id}/tasks/{taskId}/confirm with valid scan → 200 PICKED")
        void CH3_confirmPickTask_validScan_returns200WithPickedStatus() throws Exception {
                // Issue #179: CH3 — confirm endpoint must return 200 with task status=PICKED
                UUID pickListId = UUID.randomUUID();
                UUID taskId = UUID.randomUUID();
                UUID productId = UUID.randomUUID();
                UUID locationId = UUID.randomUUID();

                PickTaskResponse mockResponse = new PickTaskResponse(
                                taskId,
                                pickListId,
                                productId,
                                locationId,
                                2,
                                2,
                                PickTaskStatus.PICKED,
                                0);

                // BLOCKED: confirmPickTask does not exist on PickListService
                when(pickListService.confirmPickTask(
                                eq(pickListId), eq(taskId), eq(productId), eq(locationId), eq(2)))
                                .thenReturn(mockResponse);

                String requestBody = objectMapper.writeValueAsString(Map.of(
                                "scannedSkuId", productId.toString(),
                                "scannedLocationId", locationId.toString(),
                                "quantityPicked", 2));

                mockMvc.perform(withGatewayAuth(
                                post("/v1/inventory/pick-lists/{id}/tasks/{taskId}/confirm", pickListId, taskId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.pickTaskId").value(taskId.toString()))
                                .andExpect(jsonPath("$.status").value("PICKED"))
                                .andExpect(jsonPath("$.quantityPicked").value(2));
        }

        // ─── CH4: POST /v1/inventory/pick-lists/{id}/tasks/{taskId}/confirm — 422 ────

        /**
         * CH4: Verifies that POST /v1/inventory/pick-lists/{id}/tasks/{taskId}/confirm
         * with a scannedSkuId that does not match the task's productId returns 422
         * Unprocessable Entity per ADR-0017 (domain-policy violation).
         *
         * <p>
         * BLOCKED: {@code confirmPickTask} and {@code PickScanMismatchException} do not
         * exist — this test will not compile until the scaffold is in place.
         * After scaffold: RED because the controller endpoint does not exist (404).
         *
         * Issue: #179
         */
        @Test
        @DisplayName("POST /v1/inventory/pick-lists/{id}/tasks/{taskId}/confirm with wrong SKU → 422")
        void CH4_confirmPickTask_wrongSkuId_returns422() throws Exception {
                // Issue #179: CH4 — ADR-0017: scan mismatch is a domain-policy violation → 422
                UUID pickListId = UUID.randomUUID();
                UUID taskId = UUID.randomUUID();
                UUID wrongSkuId = UUID.randomUUID();
                UUID locationId = UUID.randomUUID();

                // BLOCKED: confirmPickTask and PickScanMismatchException do not exist
                // Issue #179: compile fix — constructor is (UUID expectedSkuId, UUID
                // scannedSkuId)
                when(pickListService.confirmPickTask(
                                eq(pickListId), eq(taskId), eq(wrongSkuId), eq(locationId), eq(1)))
                                .thenThrow(new PickScanMismatchException(UUID.randomUUID(), wrongSkuId));

                String requestBody = objectMapper.writeValueAsString(Map.of(
                                "scannedSkuId", wrongSkuId.toString(),
                                "scannedLocationId", locationId.toString(),
                                "quantityPicked", 1));

                mockMvc.perform(withGatewayAuth(
                                post("/v1/inventory/pick-lists/{id}/tasks/{taskId}/confirm", pickListId, taskId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().is(422));
        }

        // ─── CH5: GET /v1/inventory/pick-lists/{id}/tasks — 200 OK with list ─────────

        /**
         * CH5: Verifies that GET /v1/inventory/pick-lists/{id}/tasks with valid
         * gateway auth returns 200 OK with a JSON array of PickTaskResponse objects.
         *
         * <p>
         * BLOCKED: {@code getPickTasksForPickList} does not exist on
         * {@code PickListService} —
         * this test will not compile until the scaffold is in place.
         * After scaffold: RED because the controller endpoint does not exist (404).
         *
         * Issue: #179
         */
        @Test
        @DisplayName("GET /v1/inventory/pick-lists/{id}/tasks with valid auth → 200 with task list")
        void CH5_getPickTasksForPickList_validAuth_returns200WithTaskList() throws Exception {
                // Issue #179: CH5 — list-tasks endpoint must return 200 with task array
                UUID pickListId = UUID.randomUUID();
                UUID taskId = UUID.randomUUID();
                UUID productId = UUID.randomUUID();
                UUID locationId = UUID.randomUUID();

                PickTaskResponse taskResponse = new PickTaskResponse(
                                taskId,
                                pickListId,
                                productId,
                                locationId,
                                3,
                                0,
                                PickTaskStatus.PENDING,
                                0);

                // BLOCKED: getPickTasksForPickList does not exist on PickListService
                when(pickListService.getPickTasksForPickList(eq(pickListId))).thenReturn(List.of(taskResponse));

                mockMvc.perform(withGatewayAuth(get("/v1/inventory/pick-lists/{id}/tasks", pickListId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].pickTaskId").value(taskId.toString()))
                                .andExpect(jsonPath("$[0].status").value("PENDING"))
                                .andExpect(jsonPath("$[0].quantityRequired").value(3));
        }
}
