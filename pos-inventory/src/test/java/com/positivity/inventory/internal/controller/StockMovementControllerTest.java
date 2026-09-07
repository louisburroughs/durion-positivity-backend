package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.AdjustmentRequestResponse;
import com.positivity.inventory.internal.dto.CreateAdjustmentRequestDto;
import com.positivity.inventory.internal.movement.service.StockMovementService;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Web-slice tests for the adjustment endpoints' location-scope gate (ADR-0061 §3, #1871).
 *
 * <p>Create is gated in the controller on the request body's {@code locationId}; approve is gated
 * in the service on the persisted request's location, so here the service is a mock and the slice
 * verifies that its denial reaches the client as {@code 403 LOCATION_SCOPE_DENIED} rather than the
 * module's generic {@code FORBIDDEN}. {@link LocationScopeAutoConfiguration} is imported because a
 * {@code @WebMvcTest} slice does not load library auto-configuration on its own.
 */
@WebMvcTest(StockMovementController.class)
@Import({TestSecurityConfig.class, LocationScopeAutoConfiguration.class})
@ActiveProfiles("test")
@DisplayName("StockMovementController location scope")
@SuppressWarnings({"java:S6813", "java:S1192"})
class StockMovementControllerTest {

    private static final String ADJUSTMENTS = "/v1/inventory/adjustments";
    private static final String APPROVE = "/v1/inventory/adjustments/{id}/approve";

    /** The location named by the request body / carried on the adjustment request. */
    private static final UUID SHOP = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a30");
    /** The site above SHOP: assigning it puts SHOP in reach. */
    private static final UUID SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a31");
    /** A site elsewhere in the tree: assigning it leaves SHOP out of reach. */
    private static final UUID OTHER_SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a32");

    private static final UUID REQUEST_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a40");

    /** Replica stand-in: SHOP sits under SITE on both dimensions; nothing else is known. */
    private static final LocationAncestorResolver RESOLVER = locationId ->
            SHOP.equals(locationId) ? new AncestorSets(Set.of(SHOP, SITE), Set.of(SHOP, SITE)) : AncestorSets.EMPTY;

    /** The grants INVENTORY_MANAGER and INVENTORY_CONTROLLER share (#1373). */
    private static final List<SimpleGrantedAuthority> ADJUSTMENT_AUTHORITIES = List.of(
            new SimpleGrantedAuthority(InventoryPermissionRegistry.ADJUSTMENT_CREATE),
            new SimpleGrantedAuthority(InventoryPermissionRegistry.ADJUSTMENT_APPROVE),
            new SimpleGrantedAuthority(InventoryPermissionRegistry.ADJUSTMENT_VIEW));

    private static final String CREATE_JSON = """
            {"productSku":"SKU-10042",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a30",
             "quantity":-3,
             "reasonCode":"CYCLE_COUNT",
             "unitOfMeasure":"EACH"}
            """;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    Clock clock;

    @MockitoBean
    StockMovementService stockMovementService;

    @BeforeEach
    void stubClock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-09-07T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    /** A post-rollout caller with the shared grants and the given scope in the authentication details. */
    private static RequestPostProcessor caller(String username, LocationScope scope) {
        var token = new UsernamePasswordAuthenticationToken(username, null, ADJUSTMENT_AUTHORITIES);
        token.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME, username,
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE, scope));
        return authentication(token);
    }

    /** A caller whose named permission is scoped (OTHER dimension) to the given assigned nodes. */
    private static LocationScope scopedTo(String permission, UUID... nodes) {
        return LocationScope.of(Set.of(), Set.of(permission), Optional.of(Set.of(nodes)), true, RESOLVER);
    }

    /** A post-rollout caller whose grants are all global: claims present, no permission in either bitset. */
    private static LocationScope globalReach(UUID... nodes) {
        return LocationScope.of(Set.of(), Set.of(), Optional.of(Set.of(nodes)), true, RESOLVER);
    }

    private static AdjustmentRequestResponse pendingResponse() {
        return AdjustmentRequestResponse.builder()
                .adjustmentRequestId(REQUEST_ID)
                .productSku("SKU-10042")
                .locationId(SHOP)
                .quantity(new BigDecimal("-3"))
                .reasonCode("CYCLE_COUNT")
                .status("PENDING")
                .build();
    }

    @Nested
    @DisplayName("POST /v1/inventory/adjustments")
    class Create {

        @Test
        @DisplayName("locationId in reach: 201")
        void inReach_returns201() throws Exception {
            when(stockMovementService.createAdjustmentRequest(any(CreateAdjustmentRequestDto.class), eq("manager")))
                    .thenReturn(pendingResponse());

            mockMvc.perform(post(ADJUSTMENTS)
                            .with(caller("manager", scopedTo(InventoryPermissionRegistry.ADJUSTMENT_CREATE, SITE)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_JSON))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.adjustmentRequestId").value(REQUEST_ID.toString()))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        @DisplayName("locationId out of reach: 403 LOCATION_SCOPE_DENIED, service never called")
        void outOfReach_returns403LocationScopeDenied() throws Exception {
            mockMvc.perform(post(ADJUSTMENTS)
                            .with(caller(
                                    "manager", scopedTo(InventoryPermissionRegistry.ADJUSTMENT_CREATE, OTHER_SITE)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_JSON))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SHOP.toString()))));

            verify(stockMovementService, never()).createAdjustmentRequest(any(), any());
        }

        @Test
        @DisplayName("validation runs before the scope gate: bad body is 400 even out of reach")
        void invalidBody_returns400BeforeScopeGate() throws Exception {
            mockMvc.perform(post(ADJUSTMENTS)
                            .with(caller(
                                    "manager", scopedTo(InventoryPermissionRegistry.ADJUSTMENT_CREATE, OTHER_SITE)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productSku\":\"SKU-10042\",\"locationId\":\"" + SHOP + "\",\"quantity\":-3}"))
                    .andExpect(status().isBadRequest());

            verify(stockMovementService, never()).createAdjustmentRequest(any(), any());
        }

        @Test
        @DisplayName("pre-rollout token (no loc_* claims): 201, behaviour unchanged")
        void preRolloutToken_returns201() throws Exception {
            when(stockMovementService.createAdjustmentRequest(any(CreateAdjustmentRequestDto.class), any()))
                    .thenReturn(pendingResponse());

            // The module's TestAutoAuthFilter mirrors the gateway for a legacy X-Authorities
            // request: authenticated, but no LocationScope in the details.
            mockMvc.perform(post(ADJUSTMENTS)
                            .header("X-Authorities", InventoryPermissionRegistry.ADJUSTMENT_CREATE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_JSON))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("ALL-scoped caller (permission in neither bitset): 201, behaviour unchanged")
        void globalCaller_returns201() throws Exception {
            when(stockMovementService.createAdjustmentRequest(any(CreateAdjustmentRequestDto.class), eq("controller")))
                    .thenReturn(pendingResponse());

            mockMvc.perform(post(ADJUSTMENTS)
                            .with(caller("controller", globalReach(OTHER_SITE)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_JSON))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("missing create authority: 403 FORBIDDEN, distinct from the scope denial")
        void missingAuthority_returns403Forbidden() throws Exception {
            mockMvc.perform(post(ADJUSTMENTS)
                            .header("X-Authorities", InventoryPermissionRegistry.ADJUSTMENT_VIEW)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_JSON))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            verify(stockMovementService, never()).createAdjustmentRequest(any(), any());
        }
    }

    @Nested
    @DisplayName("POST /v1/inventory/adjustments/{id}/approve")
    class Approve {

        @Test
        @DisplayName("request location in reach: 200")
        void inReach_returns200() throws Exception {
            mockMvc.perform(post(APPROVE, REQUEST_ID)
                            .with(caller("manager", scopedTo(InventoryPermissionRegistry.ADJUSTMENT_APPROVE, SITE))))
                    .andExpect(status().isOk());

            verify(stockMovementService).approveAdjustmentRequest(REQUEST_ID, "manager");
        }

        @Test
        @DisplayName("service denies on scope: 403 LOCATION_SCOPE_DENIED envelope, not the generic FORBIDDEN")
        void outOfReach_returns403LocationScopeDenied() throws Exception {
            when(stockMovementService.approveAdjustmentRequest(REQUEST_ID, "manager"))
                    .thenThrow(new LocationScopeDeniedException(
                            InventoryPermissionRegistry.ADJUSTMENT_APPROVE, SHOP.toString()));

            mockMvc.perform(post(APPROVE, REQUEST_ID)
                            .with(caller(
                                    "manager", scopedTo(InventoryPermissionRegistry.ADJUSTMENT_APPROVE, OTHER_SITE))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SHOP.toString()))));
        }

        @Test
        @DisplayName("unknown request id: the existing not-found rejection (400 VALIDATION_ERROR) is unchanged")
        void missingRequest_returnsNotFoundRejection() throws Exception {
            when(stockMovementService.approveAdjustmentRequest(REQUEST_ID, "manager"))
                    .thenThrow(new IllegalArgumentException("Adjustment request not found: " + REQUEST_ID));

            mockMvc.perform(post(APPROVE, REQUEST_ID)
                            .with(caller(
                                    "manager", scopedTo(InventoryPermissionRegistry.ADJUSTMENT_APPROVE, OTHER_SITE))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("pre-rollout token (no loc_* claims): 200, behaviour unchanged")
        void preRolloutToken_returns200() throws Exception {
            mockMvc.perform(post(APPROVE, REQUEST_ID)
                            .header("X-User", "legacy-approver")
                            .header("X-Authorities", InventoryPermissionRegistry.ADJUSTMENT_APPROVE))
                    .andExpect(status().isOk());

            verify(stockMovementService).approveAdjustmentRequest(REQUEST_ID, "legacy-approver");
        }

        @Test
        @DisplayName("ALL-scoped caller: 200, behaviour unchanged")
        void globalCaller_returns200() throws Exception {
            mockMvc.perform(post(APPROVE, REQUEST_ID).with(caller("controller", globalReach(OTHER_SITE))))
                    .andExpect(status().isOk());

            verify(stockMovementService).approveAdjustmentRequest(REQUEST_ID, "controller");
        }

        @Test
        @DisplayName("missing approve authority: 403 FORBIDDEN, distinct from the scope denial")
        void missingAuthority_returns403Forbidden() throws Exception {
            mockMvc.perform(post(APPROVE, REQUEST_ID)
                            .header("X-Authorities", InventoryPermissionRegistry.ADJUSTMENT_CREATE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            verify(stockMovementService, never()).approveAdjustmentRequest(any(), any());
        }
    }
}
