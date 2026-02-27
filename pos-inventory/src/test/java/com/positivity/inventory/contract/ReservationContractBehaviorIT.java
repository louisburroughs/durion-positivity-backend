package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.reservation.CreateReservationRequest;
import com.positivity.inventory.internal.dto.reservation.PromoteAllocationRequest;
import com.positivity.inventory.internal.dto.reservation.ReservationResponse;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.exception.InsufficientAtpException;
import com.positivity.inventory.service.ReservationService;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior integration tests for the Reservation API — Story #29.
 *
 * <p>
 * Verifies the HTTP contract for reserve/allocate stock to workorder lines per:
 * <ul>
 * <li>ADR-0001: ATP = on-hand − HARD-allocated; SOFT does NOT reduce ATP</li>
 * <li>ADR-0011: gateway auth headers (X-User, X-Authorities) required</li>
 * <li>ADR-0014: internal service security — all endpoints require gateway
 * auth</li>
 * <li>ADR-0017: HTTP response codes: 201 Created, 200 OK, 204 No Content, 422,
 * 403</li>
 * <li>ADR-0023: no tenantId in request or response</li>
 * </ul>
 *
 * <p>
 * The 422 test ({@code contract_insufficientAtp_returns422}) is intentionally
 * RED:
 * {@code InsufficientAtpException} is not yet registered in
 * {@code InventoryGlobalExceptionHandler},
 * so the generic handler returns 500 instead of the expected 422.
 *
 * Issue: #29
 */
@DisplayName("Reservation Contract Behavior — Story #29")
class ReservationContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private ReservationService reservationService;

        // ─── contract_createReservation_returns201 ──────────────────────────────────

        /**
         * Verifies that POST /v1/inventory/reservations/ with a valid request returns
         * 201 Created with reservation JSON per story #29 SC1.
         *
         * Issue: #29
         */
        @Test
        @DisplayName("POST /v1/inventory/reservations/ with valid request → 201 Created with reservationId")
        void contract_createReservation_returns201() throws Exception {
                // Issue #29: create reservation endpoint must return 201 with reservationId,
                // workorderLineId, status
                UUID workorderLineId = UUID.randomUUID();
                UUID reservationId = UUID.randomUUID();

                ReservationResponse mockResponse = ReservationResponse.builder()
                                .reservationId(reservationId)
                                .workorderLineId(workorderLineId)
                                .sku("SKU-001")
                                .requiredQuantity(5)
                                .allocatedQuantity(5)
                                .status(ReservationStatus.PENDING.name())
                                .build();

                when(reservationService.createOrUpdateReservation(any(CreateReservationRequest.class)))
                                .thenReturn(mockResponse);

                String requestBody = objectMapper.writeValueAsString(
                                new CreateReservationRequest(workorderLineId, "SKU-001", 5));

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/reservations/"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.reservationId").value(reservationId.toString()))
                                .andExpect(jsonPath("$.workorderLineId").value(workorderLineId.toString()))
                                .andExpect(jsonPath("$.status").value("PENDING"))
                                .andExpect(jsonPath("$.allocatedQuantity").value(5));
        }

        // ─── contract_promoteAllocation_returns200 ──────────────────────────────────

        /**
         * Verifies that POST /v1/inventory/reservations/{allocationId}/promote returns
         * 200 OK with updated reservation when SOFT → HARD promotion succeeds per story
         * #29 SC3.
         *
         * Issue: #29
         */
        @Test
        @DisplayName("POST /v1/inventory/reservations/{allocationId}/promote → 200 OK with updated status")
        void contract_promoteAllocation_returns200() throws Exception {
                // Issue #29: promote endpoint must return 200 with reservation status after
                // hardening
                UUID allocationId = UUID.randomUUID();
                UUID workorderLineId = UUID.randomUUID();
                UUID reservationId = UUID.randomUUID();

                ReservationResponse mockResponse = ReservationResponse.builder()
                                .reservationId(reservationId)
                                .workorderLineId(workorderLineId)
                                .sku("SKU-001")
                                .requiredQuantity(5)
                                .allocatedQuantity(5)
                                .status(ReservationStatus.FULFILLED.name())
                                .build();

                when(reservationService.promoteToHard(eq(allocationId), any(PromoteAllocationRequest.class)))
                                .thenReturn(mockResponse);

                String requestBody = objectMapper.writeValueAsString(
                                new PromoteAllocationRequest("approved for workorder"));

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/reservations/{allocationId}/promote", allocationId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.reservationId").value(reservationId.toString()))
                                .andExpect(jsonPath("$.status").value("FULFILLED"));
        }

        // ─── contract_cancelReservation_returns204 ──────────────────────────────────

        /**
         * Verifies that DELETE /v1/inventory/reservations/{workorderLineId} returns
         * 204 No Content when cancellation succeeds per story #29 SC5/SC6.
         *
         * Issue: #29
         */
        @Test
        @DisplayName("DELETE /v1/inventory/reservations/{workorderLineId} → 204 No Content")
        void contract_cancelReservation_returns204() throws Exception {
                // Issue #29: cancel endpoint must return 204 with no response body
                UUID workorderLineId = UUID.randomUUID();

                doNothing().when(reservationService).cancelReservation(eq(workorderLineId));

                mockMvc.perform(withGatewayAuth(
                                delete("/v1/inventory/reservations/{workorderLineId}", workorderLineId)))
                                .andExpect(status().isNoContent());
        }

        // ─── contract_insufficientAtp_returns422 ────────────────────────────────────

        /**
         * Verifies that POST /v1/inventory/reservations/{allocationId}/promote returns
         * 422 Unprocessable Entity with error code {@code INSUFFICIENT_ATP} when
         * on-hand
         * inventory is insufficient for HARD allocation per story #29 SC4 and ADR-0017.
         *
         * <p>
         * This test is intentionally RED: {@code InventoryGlobalExceptionHandler} does
         * not
         * yet handle {@link InsufficientAtpException}, so the generic handler returns
         * 500.
         * GREEN requires adding an
         * {@code @ExceptionHandler(InsufficientAtpException.class)}
         * that maps to 422 with code {@code INSUFFICIENT_ATP}.
         *
         * Issue: #29
         */
        @Test
        @DisplayName("POST .../promote when ATP insufficient → 422 with INSUFFICIENT_ATP error code")
        void contract_insufficientAtp_returns422() throws Exception {
                // Issue #29: SC4 — InsufficientAtpException must map to 422 INSUFFICIENT_ATP
                // (RED: currently 500)
                UUID allocationId = UUID.randomUUID();

                when(reservationService.promoteToHard(eq(allocationId), any(PromoteAllocationRequest.class)))
                                .thenThrow(new InsufficientAtpException(allocationId, 10, 3));

                String requestBody = objectMapper.writeValueAsString(
                                new PromoteAllocationRequest("urgent hardening"));

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/reservations/{allocationId}/promote", allocationId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().is(422))
                                .andExpect(jsonPath("$.code").value("INSUFFICIENT_ATP"));
        }

        // ─── contract_missingGatewayAuth_returns403 ─────────────────────────────────

        /**
         * Verifies that POST /v1/inventory/reservations/ without the required gateway
         * X-Authorities header returns 403 Forbidden per ADR-0011 and ADR-0014.
         *
         * Issue: #29
         */
        @Test
        @DisplayName("POST /v1/inventory/reservations/ without X-Authorities → 403 Forbidden")
        void contract_missingGatewayAuth_returns403() throws Exception {
                // Issue #29: ADR-0011/ADR-0014 — missing X-Authorities header must yield 403
                // Forbidden
                UUID workorderLineId = UUID.randomUUID();
                String requestBody = objectMapper.writeValueAsString(
                                new CreateReservationRequest(workorderLineId, "SKU-001", 5));

                mockMvc.perform(post("/v1/inventory/reservations/")
                                .header("X-User", "test-user")
                                // Deliberately omit X-Authorities to trigger 403
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isForbidden());
        }
}
