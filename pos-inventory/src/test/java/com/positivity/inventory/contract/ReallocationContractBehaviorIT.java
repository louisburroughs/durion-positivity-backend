package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.reallocation.ReallocateRequest;
import com.positivity.inventory.internal.dto.reallocation.ReallocateResponse;
import com.positivity.inventory.service.AllocationReallocationService;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior integration tests for the Reallocation API — Story #24
 * (CAP-220): Deterministic Reallocation on Priority Change.
 *
 * <p>
 * Verifies the HTTP contract for
 * {@code POST /v1/inventory/allocations/reallocate}
 * per:
 * <ul>
 * <li>ADR-0011: gateway auth headers (X-User, X-Authorities) required</li>
 * <li>ADR-0013: UUIDv7 identifiers
 * (UUID.fromString("00000000-0000-0000-0000-000000000001") used in tests)</li>
 * <li>ADR-0017: HTTP response codes — 200 for successful mutation, 400 for
 * missing required fields, 403 for missing authority</li>
 * <li>ADR-0018: actor sourced from X-User security context header, not request
 * body</li>
 * <li>ADR-0023: no tenantId in request or response</li>
 * </ul>
 *
 * <p>
 * All tests mock {@link AllocationReallocationService} via
 * {@code @MockitoBean}.
 * The HTTP contract assertions are GREEN immediately; the behavioral
 * correctness
 * (sort order, full-allocation rule, ATP consistency) is validated in
 * {@code AllocationReallocationServiceImplTest} (RED phase).
 *
 * Issue: #24
 */
@DisplayName("Reallocation Contract Behavior — Story #24 (CAP-220)")
class ReallocationContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private AllocationReallocationService allocationReallocationService;

        /**
         * Provides gateway auth headers including the
         * {@code inventory:allocations:reallocate}
         * authority required by
         * {@link com.positivity.inventory.internal.controller.ReallocationController}.
         *
         * <p>
         * The base {@link BaseContractIntegrationTest#withGatewayAuth} helper does not
         * include this authority, so a dedicated helper is required per ADR-0011.
         */
        private MockHttpServletRequestBuilder withReallocateAuth(MockHttpServletRequestBuilder requestBuilder) {
                return requestBuilder
                                .header("X-User", "contract-test-user")
                                .header("X-Authorities", "inventory:allocations:reallocate");
        }

        // ─── AC1: Deterministic reallocation returns 200 with valid response body ───

        /**
         * AC1: Verifies that POST /v1/inventory/allocations/reallocate with a valid
         * request and gateway reallocation authority returns 200 OK with
         * {@code stockItemId}, {@code totalReallocated}, {@code auditRecordsCreated},
         * and {@code atpAfterReallocation} fields per ADR-0017.
         *
         * Issue: #24
         */
        @Test
        @DisplayName("POST /v1/inventory/allocations/reallocate with valid request → 200 OK with reallocation response")
        void contract_reallocate_withValidRequest_returns200WithResponseBody() throws Exception {
                // Issue #24: AC1 — reallocate endpoint must return 200 with stockItemId,
                // totalReallocated, auditRecordsCreated, atpAfterReallocation
                UUID stockItemId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                ReallocateResponse mockResponse = ReallocateResponse.builder()
                                .stockItemId(stockItemId)
                                .totalReallocated(3)
                                .auditRecordsCreated(2)
                                .atpAfterReallocation(7)
                                .build();

                when(allocationReallocationService.reallocate(any(ReallocateRequest.class)))
                                .thenReturn(mockResponse);

                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType("PRIORITY_CHANGE")
                                .triggerReferenceId("workorder-ref-001")
                                .build();

                mockMvc.perform(withReallocateAuth(post("/v1/inventory/allocations/reallocate"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.stockItemId").value(stockItemId.toString()))
                                .andExpect(jsonPath("$.totalReallocated").value(3))
                                .andExpect(jsonPath("$.auditRecordsCreated").value(2))
                                .andExpect(jsonPath("$.atpAfterReallocation").value(7));
        }

        // ─── AC1: Audit record created in response ───────────────────────────────────

        /**
         * AC1: Verifies that the response includes {@code auditRecordsCreated > 0}
         * when the service indicates a PRIORITY_CHANGE reallocation occurred,
         * consistent
         * with the audit trail requirement for the PRIORITY_CHANGE reason code per #24.
         *
         * Issue: #24
         */
        @Test
        @DisplayName("POST /v1/inventory/allocations/reallocate → response contains auditRecordsCreated > 0 when reallocation occurred")
        void contract_reallocate_priorityChange_responseIncludesAuditCount() throws Exception {
                // Issue #24: AC1 — audit record creation must be reflected in
                // auditRecordsCreated field
                UUID stockItemId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                ReallocateResponse mockResponse = ReallocateResponse.builder()
                                .stockItemId(stockItemId)
                                .totalReallocated(5)
                                .auditRecordsCreated(2)
                                .atpAfterReallocation(0)
                                .build();

                when(allocationReallocationService.reallocate(any(ReallocateRequest.class)))
                                .thenReturn(mockResponse);

                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType("PRIORITY_CHANGE")
                                .triggerReferenceId("cap220-ref-002")
                                .build();

                mockMvc.perform(withReallocateAuth(post("/v1/inventory/allocations/reallocate"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.auditRecordsCreated").value(2));
        }

        // ─── AC2 / AC3: No partial allocation — response reflects full-only totals ──

        /**
         * AC2 / AC3: Verifies that when the service returns a response reflecting
         * full-only allocation (some workorders get 0), the endpoint serialises the
         * response correctly with the correct {@code totalReallocated} and
         * {@code atpAfterReallocation}.
         *
         * <p>
         * The full-allocation-only and ATP-consistency enforcement is tested at the
         * service layer in {@code AllocationReallocationServiceImplTest}.
         *
         * Issue: #24
         */
        @Test
        @DisplayName("POST /v1/inventory/allocations/reallocate → response serialises totalReallocated and atpAfterReallocation correctly")
        void contract_reallocate_atpAndTotalReallocatedInResponse() throws Exception {
                // Issue #24: AC2/AC3 — response must carry totalReallocated and
                // atpAfterReallocation
                UUID stockItemId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                ReallocateResponse mockResponse = ReallocateResponse.builder()
                                .stockItemId(stockItemId)
                                .totalReallocated(10)
                                .auditRecordsCreated(1)
                                .atpAfterReallocation(0)
                                .build();

                when(allocationReallocationService.reallocate(any(ReallocateRequest.class)))
                                .thenReturn(mockResponse);

                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType("PRIORITY_CHANGE")
                                .triggerReferenceId("cap220-ref-003")
                                .build();

                mockMvc.perform(withReallocateAuth(post("/v1/inventory/allocations/reallocate"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.totalReallocated").value(10))
                                .andExpect(jsonPath("$.atpAfterReallocation").value(0));
        }

        // ─── AC4: Missing stockItemId → 400 Bad Request ─────────────────────────────

        /**
         * AC4: Verifies that POST /v1/inventory/allocations/reallocate with a missing
         * (null) {@code stockItemId} field returns 400 Bad Request per:
         * <ul>
         * <li>Bean Validation: {@code @NotNull} on
         * {@code ReallocateRequest.stockItemId}</li>
         * <li>ADR-0017: field validation → 400</li>
         * </ul>
         *
         * Issue: #24
         */
        @Test
        @DisplayName("POST /v1/inventory/allocations/reallocate with missing stockItemId → 400 Bad Request")
        void contract_reallocate_missingSku_returns400() throws Exception {
                // Issue #24: AC4 — missing stockItemId must produce 400 (bean validation via
                // @NotNull)
                String requestBody = "{\"triggerType\":\"PRIORITY_CHANGE\",\"triggerReferenceId\":\"ref-007\"}";

                mockMvc.perform(withReallocateAuth(post("/v1/inventory/allocations/reallocate"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isBadRequest());
        }

        // ─── AC5: No auth → 403 Forbidden ────────────────────────────────────────────

        /**
         * AC5: Verifies that POST /v1/inventory/allocations/reallocate without the
         * required {@code inventory:allocations:reallocate} authority returns 403
         * Forbidden per ADR-0011 and ADR-0014.
         *
         * <p>
         * The request includes a valid X-User header but omits X-Authorities, so the
         * security context has no authorities and {@code @PreAuthorize} on the
         * controller denies access.
         *
         * Issue: #24
         */
        @Test
        @DisplayName("POST /v1/inventory/allocations/reallocate without X-Authorities → 403 Forbidden")
        void contract_reallocate_withoutAuthority_returns403() throws Exception {
                // Issue #24: AC5 — missing inventory:allocations:reallocate authority must
                // return 403
                UUID stockItemId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType("PRIORITY_CHANGE")
                                .triggerReferenceId("ref-noauth")
                                .build();

                mockMvc.perform(post("/v1/inventory/allocations/reallocate")
                                .header("X-User", "test-user")
                                // Deliberately omit X-Authorities — triggers 403 via @PreAuthorize
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isForbidden());
        }
}
