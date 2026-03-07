package com.positivity.inventory.contract;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.consumption.ConsumeItemLine;
import com.positivity.inventory.internal.dto.consumption.ConsumeItemsRequest;
import com.positivity.inventory.internal.dto.consumption.ConsumptionResponse;
import com.positivity.inventory.internal.exception.WorkorderConsumptionException;
import com.positivity.inventory.service.ConsumptionService;

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
 * Contract behavior integration tests for the Consumption API — Story #178:
 * Issue/Consume Picked Items to Workorder.
 *
 * <p>
 * Tests CC1, CC3, CC4 are intentionally RED: the {@code ConsumptionController}
 * is a hardcoded stub and does not delegate to {@code ConsumptionService}. CC1
 * fails
 * because the consumptionId in the response is randomly generated (not sourced
 * from the
 * mocked service). CC3 and CC4 fail because the stub never invokes the service
 * to raise
 * domain exceptions, returning 201 instead of 422/400.
 *
 * <p>
 * CC2 (missing auth → 403) is expected to pass in RED phase because
 * {@code ConsumptionController} already carries a {@code @PreAuthorize} guard.
 *
 * <p>
 * ADR compliance:
 * <ul>
 * <li>ADR-0011: gateway auth headers (X-User, X-Authorities) required</li>
 * <li>ADR-0013: UUIDv7 identifier for consumptionId</li>
 * <li>ADR-0014: internal service security — all state-changing endpoints
 * require gateway auth</li>
 * <li>ADR-0017: HTTP response codes: 201 Created, 400 Bad Request, 403
 * Forbidden, 422 Unprocessable Entity</li>
 * <li>ADR-0018: transactionUserId sourced from X-User header via security
 * context</li>
 * </ul>
 *
 * Issue: #178
 */
@DisplayName("Consumption Contract Behavior — Story #178")
class ConsumptionContractBehaviorIT extends BaseContractIntegrationTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private ConsumptionService consumptionService;

        // ─── CC1: POST /v1/inventory/consumption — 201 Created, consumptionId from
        // service ──

        /**
         * CC1: Verifies that POST /v1/inventory/consumption with a valid request body
         * and
         * gateway auth returns 201 Created, and that the {@code consumptionId} in the
         * response
         * originates from the {@code ConsumptionService} mock (not a
         * controller-generated UUID).
         *
         * <p>
         * RED: {@code ConsumptionController} does not delegate to
         * {@code ConsumptionService};
         * it generates its own random UUID, so the {@code consumptionId} assertion
         * fails.
         *
         * Issue: #178
         */
        @Test
        @DisplayName("POST /v1/inventory/consumption with valid body + auth → 201 Created with service consumptionId")
        void CC1_consumePickedItems_validBodyAndAuth_returns201WithServiceConsumptionId() throws Exception {
                // Issue #178: CC1 — endpoint must delegate to ConsumptionService and return its
                // consumptionId in the response body

                // Arrange
                UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID pickListId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID expectedConsumptionId = UUID.fromString("00000000-0000-0000-0000-000000000042");

                ConsumptionResponse mockResponse = new ConsumptionResponse(
                                expectedConsumptionId,
                                workorderId,
                                pickListId,
                                1,
                                Instant.now(TEST_CLOCK),
                                List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")));

                when(consumptionService.consumePickedItems(any(ConsumeItemsRequest.class)))
                                .thenReturn(mockResponse);

                ConsumeItemsRequest requestBody = new ConsumeItemsRequest(
                                workorderId,
                                pickListId,
                                List.of(new ConsumeItemLine(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                                UUID.fromString("00000000-0000-0000-0000-000000000001"), 1)));

                // Act + Assert — RED: controller returns 201 but with a randomly generated
                // consumptionId, not expectedConsumptionId
                mockMvc.perform(withGatewayAuth(post("/v1/inventory/consumption"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.consumptionId").value(expectedConsumptionId.toString()))
                                .andExpect(jsonPath("$.workorderId").value(workorderId.toString()))
                                .andExpect(jsonPath("$.totalItemsConsumed").value(1));
        }

        // ─── CC2: POST /v1/inventory/consumption — 403 without gateway auth ──────────

        /**
         * CC2: Verifies that POST /v1/inventory/consumption without the required
         * X-Authorities header returns 403 Forbidden per ADR-0011 and ADR-0014.
         *
         * <p>
         * This test is expected to PASS in the RED phase because
         * {@code ConsumptionController} already carries {@code @PreAuthorize}.
         *
         * Issue: #178
         */
        @Test
        @DisplayName("POST /v1/inventory/consumption without gateway auth → 403 Forbidden")
        void CC2_consumePickedItems_missingGatewayAuth_returns403() throws Exception {
                // Issue #178: ADR-0011/ADR-0014 — missing X-Authorities header must yield 403

                ConsumeItemsRequest requestBody = new ConsumeItemsRequest(
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                List.of(new ConsumeItemLine(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                                UUID.fromString("00000000-0000-0000-0000-000000000001"), 1)));

                mockMvc.perform(post("/v1/inventory/consumption")
                                .header("X-User", "test-user")
                                // Deliberately omit X-Authorities to trigger 403
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().isForbidden());
        }

        // ─── CC3: POST /v1/inventory/consumption — 422 for unpicked task ─────────────

        /**
         * CC3: Verifies that POST /v1/inventory/consumption returns 422 Unprocessable
         * Entity
         * when the referenced pick task is not in PICKED status (service throws
         * {@link WorkorderConsumptionException}).
         *
         * <p>
         * RED: {@code ConsumptionController} does not call {@code ConsumptionService};
         * the stub returns 201 regardless of task status.
         *
         * Issue: #178
         */
        @Test
        @DisplayName("POST /v1/inventory/consumption with unpicked task → 422 Unprocessable Entity")
        void CC3_consumePickedItems_unpickedTask_returns422() throws Exception {
                // Issue #178: CC3 — service throws WorkorderConsumptionException for non-PICKED
                // tasks;
                // controller must translate this to 422

                when(consumptionService.consumePickedItems(any(ConsumeItemsRequest.class)))
                                .thenThrow(new WorkorderConsumptionException("Item not picked"));

                ConsumeItemsRequest requestBody = new ConsumeItemsRequest(
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                List.of(new ConsumeItemLine(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                                UUID.fromString("00000000-0000-0000-0000-000000000001"), 1)));

                // Act + Assert — RED: controller stub returns 201, not 422
                mockMvc.perform(withGatewayAuth(post("/v1/inventory/consumption"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().is(422));
        }

        // ─── CC4: POST /v1/inventory/consumption — 400 for null workorderId ──────────

        /**
         * CC4: Verifies that POST /v1/inventory/consumption returns 400 Bad Request
         * when
         * the request body has a null workorderId.
         *
         * <p>
         * RED: {@code ConsumptionController} does not validate the request body;
         * the stub accepts null workorderId and returns 201.
         *
         * Issue: #178
         */
        @Test
        @DisplayName("POST /v1/inventory/consumption with null workorderId → 400 Bad Request")
        void CC4_consumePickedItems_nullWorkorderId_returns400() throws Exception {
                // Issue #178: CC4 — null workorderId must be rejected with 400 (bean validation
                // via @NotNull on workorderId field is expected in GREEN)

                ConsumeItemsRequest requestBody = new ConsumeItemsRequest(
                                null, // null workorderId — must be rejected
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                List.of(new ConsumeItemLine(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                                UUID.fromString("00000000-0000-0000-0000-000000000001"), 1)));

                // Act + Assert — RED: controller stub accepts null and returns 201, not 400
                mockMvc.perform(withGatewayAuth(post("/v1/inventory/consumption"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().isBadRequest());
        }
}
