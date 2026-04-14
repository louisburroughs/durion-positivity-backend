package com.positivity.vehiclefitment.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.vehiclefitment.BaseContractIntegrationTest;
import com.positivity.vehiclefitment.internal.dto.FilterProductsResponse;
import com.positivity.vehiclefitment.internal.dto.FitmentTagDto;
import com.positivity.vehiclefitment.internal.dto.HintResponse;
import com.positivity.vehiclefitment.internal.entity.TagType;
import com.positivity.vehiclefitment.service.VehicleApplicabilityHintService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract behavior tests for {@link VehicleApplicabilityHintController}.
 *
 * <p>ADR compliance:</p>
 * <ul>
 *   <li>ADR-0017: 201 for create, 200 for update/read/filter, 204 for delete,
 *       400 for validation failures, 401 for unauthenticated requests, 404 for
 *       not-found conditions.</li>
 *   <li>ADR-0018: actor resolved from SecurityContext via gateway headers,
 *       not from request body.</li>
 *   <li>ADR-0026: controller depends only on service interface, never on impl.</li>
 * </ul>
 *
 * Issue: #44
 */
@DisplayName("VehicleApplicabilityHintController — contract behavior tests")
class VehicleApplicabilityHintControllerTest extends BaseContractIntegrationTest {

    private static final UUID TEST_PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEST_HINT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VehicleApplicabilityHintService hintService;

    // ─────────────────────────────────────────────────────────────────────────
    // VAH-C001: unauthenticated request must be rejected with 401
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * VAH-C001: no gateway auth headers → 401 Unauthorized.
     *
     * <p>ADR-0017 §401 — unauthenticated calls must never reach the service.</p>
     *
     * Issue: #44
     */
    @Test
    @DisplayName("VAH-C001: no auth headers → 401")
    void VAH_C001_unauthenticated_creates_returns401() throws Exception {
        mockMvc.perform(post("/v1/vehicle-fitment/hints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateHintBody(TEST_PRODUCT_ID)))
                .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VAH-C002: authenticated valid create → 201 with hintId
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * VAH-C002: authenticated request with valid body → 201 Created with
     * non-null {@code hintId} in response body.
     *
     * <p>ADR-0017 §201 — resource creation must return 201.</p>
     * <p>Acceptance criterion AC1: Create Fitment Hint saves and emits
     * {@code VEHICLE_HINT_CREATED} event (event ID tested in audit tests).</p>
     *
     * Issue: #44
     */
    @Test
    @DisplayName("VAH-C002: authenticated valid hint → 201 with hintId")
    void VAH_C002_authenticated_validHint_creates_returns201() throws Exception {
        HintResponse serviceResponse = buildHintResponse(TEST_HINT_ID, TEST_PRODUCT_ID);
        when(hintService.createHint(any())).thenReturn(serviceResponse);

        mockMvc.perform(withGatewayAuth(post("/v1/vehicle-fitment/hints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateHintBody(TEST_PRODUCT_ID))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hintId").value(TEST_HINT_ID.toString()))
                .andExpect(jsonPath("$.productId").value(TEST_PRODUCT_ID.toString()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VAH-C003: authenticated valid update → 200
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * VAH-C003: authenticated PUT with valid body → 200 OK with updated hint.
     *
     * <p>ADR-0017 §200 — successful mutation must return 200.</p>
     * <p>Acceptance criterion AC2: Update Fitment Hint saves and emits
     * {@code VEHICLE_HINT_UPDATED} event (event ID tested in audit tests).</p>
     *
     * Issue: #44
     */
    @Test
    @DisplayName("VAH-C003: authenticated update hint → 200")
    void VAH_C003_authenticated_updateHint_returns200() throws Exception {
        HintResponse serviceResponse = buildHintResponse(TEST_HINT_ID, TEST_PRODUCT_ID);
        when(hintService.updateHint(eq(TEST_HINT_ID), any())).thenReturn(serviceResponse);

        mockMvc.perform(withGatewayAuth(put("/v1/vehicle-fitment/hints/{hintId}", TEST_HINT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateHintBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hintId").value(TEST_HINT_ID.toString()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VAH-C004: authenticated valid delete → 204
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * VAH-C004: authenticated DELETE → 204 No Content.
     *
     * <p>ADR-0017 §204 — successful deletion must return 204.</p>
     *
     * Issue: #44
     */
    @Test
    @DisplayName("VAH-C004: authenticated delete hint → 204")
    void VAH_C004_authenticated_deleteHint_returns204() throws Exception {
        doNothing().when(hintService).deleteHint(TEST_HINT_ID);

        mockMvc.perform(withGatewayAuth(delete("/v1/vehicle-fitment/hints/{hintId}", TEST_HINT_ID)))
                .andExpect(status().isNoContent());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VAH-C005: filter by vehicle attributes → 200 with matching products
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * VAH-C005: authenticated filter request with attributes that match products
     * → 200 OK with non-empty product list.
     *
     * <p>ADR-0017 §200 — successful query must return 200.</p>
     * <p>Acceptance criterion AC3: Filter Products by Vehicle Attributes returns
     * matching products.</p>
     *
     * Issue: #44
     */
    @Test
    @DisplayName("VAH-C005: filter by vehicle attributes returns matching products → 200")
    void VAH_C005_filterByVehicleAttributes_returnsMatchingProducts_200() throws Exception {
        String matchingProductId = TEST_PRODUCT_ID.toString();
        FilterProductsResponse serviceResponse = new FilterProductsResponse(List.of(matchingProductId), 1);
        when(hintService.filterProductsByVehicleAttributes(any())).thenReturn(serviceResponse);

        mockMvc.perform(withGatewayAuth(post("/v1/vehicle-fitment/hints/filter-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleAttributes\":{\"make\":\"Toyota\"}}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productIds[0]").value(matchingProductId))
                .andExpect(jsonPath("$.count").value(1));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VAH-C006: filter by vehicle attributes with no match → 200 + empty list
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * VAH-C006: authenticated filter request with attributes that match nothing
     * → 200 OK with empty list (NOT 404).
     *
     * <p>ADR-0017 §200 — empty search result must return 200, not 404.</p>
     * <p>Acceptance criterion AC4: Filter with no match → 200 + empty list.</p>
     *
     * Issue: #44
     */
    @Test
    @DisplayName("VAH-C006: filter with no match → 200 with empty productIds")
    void VAH_C006_filterByVehicleAttributes_noMatch_returns200Empty() throws Exception {
        when(hintService.filterProductsByVehicleAttributes(any())).thenReturn(new FilterProductsResponse(List.of(), 0));

        mockMvc.perform(withGatewayAuth(post("/v1/vehicle-fitment/hints/filter-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleAttributes\":{\"make\":\"Nonexistent\"}}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productIds").isEmpty())
                .andExpect(jsonPath("$.count").value(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VAH-C007: malformed payload → 400
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * VAH-C007: authenticated POST with missing required fitmentTags field
     * → 400 Bad Request (Bean Validation via {@code @Valid}).
     *
     * <p>ADR-0017 §400 — validation failures must return 400.</p>
     * <p>Acceptance criterion AC5: Malformed/invalid tag → 400 Bad Request.</p>
     *
     * Issue: #44
     */
    @Test
    @DisplayName("VAH-C007: missing fitmentTags → 400")
    void VAH_C007_malformedTagPayload_returns400() throws Exception {
        // Missing fitmentTags field — @NotEmpty validation must reject this
        mockMvc.perform(withGatewayAuth(post("/v1/vehicle-fitment/hints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + TEST_PRODUCT_ID + "\"}")))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VAH-C008: non-existent product → service throws → 404
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * VAH-C008: service throws {@link IllegalArgumentException} (product not found)
     * → controller must return 404 Not Found.
     *
     * <p>ADR-0017 §404 — unknown resource references must return 404.</p>
     * <p>Acceptance criterion AC6: Non-existent product for hint → 404 Not Found.</p>
     *
     * Issue: #44
     */
    @Test
    @DisplayName("VAH-C008: non-existent product, service throws → 404")
    void VAH_C008_nonExistentProduct_serviceThrows_returns404() throws Exception {
        doThrow(new IllegalArgumentException("Product not found"))
                .when(hintService)
                .createHint(any());

        mockMvc.perform(withGatewayAuth(post("/v1/vehicle-fitment/hints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateHintBody(UUID.randomUUID()))))
                .andExpect(status().isNotFound());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String validCreateHintBody(UUID productId) {
        return """
                {
                  "productId": "%s",
                  "fitmentTags": [
                    {"tagType": "MAKE", "tagValue": "Toyota"},
                    {"tagType": "MODEL", "tagValue": "Camry"}
                  ]
                }
                """.formatted(productId);
    }

    private String validUpdateHintBody() {
        return """
                {
                  "fitmentTags": [
                    {"tagType": "MAKE", "tagValue": "Honda"}
                  ]
                }
                """;
    }

    private HintResponse buildHintResponse(UUID hintId, UUID productId) {
        return new HintResponse(
                hintId.toString(),
                productId.toString(),
                List.of(new FitmentTagDto(TagType.MAKE, "Toyota")),
                LocalDateTime.now(),
                LocalDateTime.now(),
                "contract-test-user",
                "contract-test-user");
    }
}
