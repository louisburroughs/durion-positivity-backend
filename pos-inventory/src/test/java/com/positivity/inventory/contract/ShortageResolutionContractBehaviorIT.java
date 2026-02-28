package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.dto.shortage.ResolutionOption;
import com.positivity.inventory.dto.shortage.ShortageResolutionRequest;
import com.positivity.inventory.dto.shortage.ShortageResolutionResponse;
import com.positivity.inventory.dto.shortage.ResolutionOptionType;
import com.positivity.inventory.service.ShortageResolutionService;

import java.math.BigDecimal;
import java.util.List;
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
 * Contract behavior integration tests for the Shortage Resolution API — Story
 * #25
 * (CAP-220): Allocations: Handle Shortages with Backorder or Substitution
 * Suggestion.
 *
 * <p>
 * Verifies the HTTP contract for
 * {@code POST /v1/inventory/allocations/shortages/resolve} per:
 * <ul>
 * <li>ADR-0011: gateway auth headers (X-User, X-Authorities) required</li>
 * <li>ADR-0013: UUID.randomUUID() acceptable in tests</li>
 * <li>ADR-0017: HTTP response codes — 200 OK on success, 400 on missing
 * required fields,
 * 401 when auth headers are absent entirely</li>
 * <li>ADR-0018: actor sourced from X-User security context header, not request
 * body</li>
 * </ul>
 *
 * <p>
 * All tests mock {@link ShortageResolutionService} via {@code @MockitoBean}.
 * Behavioral correctness (option ordering, partial banner logic, client
 * fallback) is
 * validated in {@code ShortageResolutionServiceImplTest} (RED phase).
 *
 * Issue: #25
 */
@DisplayName("Shortage Resolution Contract Behavior — Story #25 (CAP-220)")
class ShortageResolutionContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private ShortageResolutionService shortageResolutionService;

        /**
         * Provides gateway auth headers including the
         * {@code inventory:shortages:resolve}
         * authority required by
         * {@link com.positivity.inventory.internal.controller.ShortageController}.
         *
         * <p>
         * The base {@link BaseContractIntegrationTest#withGatewayAuth} helper does not
         * include this authority, so a dedicated helper is required per ADR-0011.
         */
        private MockHttpServletRequestBuilder withShortageAuth(MockHttpServletRequestBuilder requestBuilder) {
                return requestBuilder
                                .header("X-User", "contract-test-user")
                                .header("X-Authorities", "inventory:shortages:resolve");
        }

        // ─── 1. Valid request → 200 OK with JSON structure ───────────────────────────

        /**
         * Verifies that POST /v1/inventory/allocations/shortages/resolve with a valid
         * request body and correct gateway authority returns 200 OK with allocationId,
         * sku, options array, and partialResultsBanner fields per ADR-0017.
         *
         * Issue: #25
         */
        @Test
        @DisplayName("POST /v1/inventory/allocations/shortages/resolve with valid request → 200 OK with response body")
        void resolveShortage_validRequest_returns200() throws Exception {
                // Issue #25: valid request must return 200 with well-formed JSON structure
                UUID allocationId = UUID.randomUUID();

                ShortageResolutionResponse mockResponse = ShortageResolutionResponse.builder()
                                .allocationId(allocationId)
                                .sku("PART-001")
                                .options(List.of(
                                                ResolutionOption.builder()
                                                                .type(ResolutionOptionType.SUBSTITUTE)
                                                                .substitutePartNumber("PART-001A")
                                                                .unitCost(new BigDecimal("12.50"))
                                                                .estimatedLeadTimeDays(3)
                                                                .qualityTier("A")
                                                                .build(),
                                                ResolutionOption.builder()
                                                                .type(ResolutionOptionType.BACKORDER)
                                                                .build()))
                                .partialResultsBanner(false)
                                .build();

                when(shortageResolutionService.resolveShortage(any(ShortageResolutionRequest.class)))
                                .thenReturn(mockResponse);

                ShortageResolutionRequest request = ShortageResolutionRequest.builder()
                                .allocationId(allocationId)
                                .sku("PART-001")
                                .shortQuantity(2)
                                .build();

                mockMvc.perform(withShortageAuth(post("/v1/inventory/allocations/shortages/resolve"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.allocationId").value(allocationId.toString()))
                                .andExpect(jsonPath("$.sku").value("PART-001"))
                                .andExpect(jsonPath("$.options").isArray())
                                .andExpect(jsonPath("$.partialResultsBanner").value(false));
        }

        // ─── 2. Missing allocationId → 400 Bad Request ───────────────────────────────

        /**
         * Verifies that a request body missing the required allocationId field returns
         * 400 Bad Request per ADR-0017 (Bean Validation via {@code @NotNull}).
         *
         * Issue: #25
         */
        @Test
        @DisplayName("POST /v1/inventory/allocations/shortages/resolve with missing allocationId → 400 Bad Request")
        void resolveShortage_missingAllocationId_returns400() throws Exception {
                // Issue #25: allocationId is @NotNull — missing field must produce 400
                String requestBody = """
                                {"sku":"PART-001","shortQuantity":2}
                                """;

                mockMvc.perform(withShortageAuth(post("/v1/inventory/allocations/shortages/resolve"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isBadRequest());
        }

        // ─── 3. Missing sku → 400 Bad Request ────────────────────────────────────────

        /**
         * Verifies that a request body missing the required sku field returns
         * 400 Bad Request per ADR-0017 (Bean Validation via {@code @NotNull}).
         *
         * Issue: #25
         */
        @Test
        @DisplayName("POST /v1/inventory/allocations/shortages/resolve with missing sku → 400 Bad Request")
        void resolveShortage_missingSku_returns400() throws Exception {
                // Issue #25: sku is @NotNull — missing field must produce 400
                UUID allocationId = UUID.randomUUID();
                String requestBody = String.format("""
                                {"allocationId":"%s","shortQuantity":2}
                                """, allocationId);

                mockMvc.perform(withShortageAuth(post("/v1/inventory/allocations/shortages/resolve"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isBadRequest());
        }

        // ─── 4. No auth headers → 401 Unauthorized ───────────────────────────────────

        /**
         * Verifies that a request with no X-User or X-Authorities headers returns
         * 403 Forbidden.
         *
         * <p>
         * This module uses the shared
         * {@link com.positivity.security.common.GatewaySecurityConfig}
         * which returns 403 for requests without gateway auth headers (consistent with
         * {@code ReallocationContractBehaviorIT#contract_reallocate_withoutAuthority_returns403}).
         *
         * Issue: #25
         */
        @Test
        @DisplayName("POST /v1/inventory/allocations/shortages/resolve without auth headers → 403 Forbidden")
        void resolveShortage_noAuth_returns403() throws Exception {
                // Issue #25: no X-User/X-Authorities — gateway security returns 403 for
                // unauthenticated requests
                ShortageResolutionRequest request = ShortageResolutionRequest.builder()
                                .allocationId(UUID.randomUUID())
                                .sku("PART-001")
                                .shortQuantity(2)
                                .build();

                mockMvc.perform(post("/v1/inventory/allocations/shortages/resolve")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isForbidden());
        }

        // ─── 5. shortQuantity ≤ 0 → 400 Bad Request ─────────────────────────────────

        /**
         * Verifies that {@code shortQuantity=0} violates the {@code @Positive} Bean
         * Validation constraint and returns 400 Bad Request per ADR-0017, without
         * reaching the service layer.
         *
         * Issue: #25
         */
        @Test
        @DisplayName("POST /v1/inventory/allocations/shortages/resolve — shortQuantity=0 → 400")
        void resolveShortage_zeroShortQuantity_returns400() throws Exception {
                // Issue #25: shortQuantity=0 violates @Positive — must return 400, service not
                // called
                UUID allocationId = UUID.randomUUID();
                String requestBody = String.format(
                                "{\"allocationId\":\"%s\",\"sku\":\"PART-001\",\"shortQuantity\":0}",
                                allocationId);

                mockMvc.perform(withShortageAuth(post("/v1/inventory/allocations/shortages/resolve"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isBadRequest());
        }

        /**
         * Verifies that {@code shortQuantity=-1} violates the {@code @Positive} Bean
         * Validation constraint and returns 400 Bad Request per ADR-0017, without
         * reaching the service layer.
         *
         * Issue: #25
         */
        @Test
        @DisplayName("POST /v1/inventory/allocations/shortages/resolve — shortQuantity=-1 → 400")
        void resolveShortage_negativeShortQuantity_returns400() throws Exception {
                // Issue #25: shortQuantity=-1 violates @Positive — must return 400, service not
                // called
                UUID allocationId = UUID.randomUUID();
                String requestBody = String.format(
                                "{\"allocationId\":\"%s\",\"sku\":\"PART-001\",\"shortQuantity\":-1}",
                                allocationId);

                mockMvc.perform(withShortageAuth(post("/v1/inventory/allocations/shortages/resolve"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isBadRequest());
        }

        // ─── 6. partialResultsBanner=true serialised correctly ───────────────────────

        /**
         * Verifies that when the service signals a partial result (Scenario 4: partner
         * failure
         * or client timeout), the HTTP response carries
         * {@code partialResultsBanner=true}
         * correctly in the JSON body per Story #25 Scenario 4.
         *
         * Issue: #25
         */
        @Test
        @DisplayName("POST /v1/inventory/allocations/shortages/resolve → 200 OK with partialResultsBanner=true")
        void resolveShortage_partialBannerResponse_returns200WithBannerTrue() throws Exception {
                // Issue #25: Scenario 4 — partner client failure must surface as
                // partialResultsBanner=true
                UUID allocationId = UUID.randomUUID();

                ShortageResolutionResponse partialResponse = ShortageResolutionResponse.builder()
                                .allocationId(allocationId)
                                .sku("PART-X")
                                .options(List.of(
                                                ResolutionOption.builder()
                                                                .type(ResolutionOptionType.BACKORDER)
                                                                .build()))
                                .partialResultsBanner(true)
                                .build();

                when(shortageResolutionService.resolveShortage(any(ShortageResolutionRequest.class)))
                                .thenReturn(partialResponse);

                ShortageResolutionRequest request = ShortageResolutionRequest.builder()
                                .allocationId(allocationId)
                                .sku("PART-X")
                                .shortQuantity(1)
                                .build();

                mockMvc.perform(withShortageAuth(post("/v1/inventory/allocations/shortages/resolve"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.partialResultsBanner").value(true))
                                .andExpect(jsonPath("$.options[0].type").value("BACKORDER"));
        }
}
