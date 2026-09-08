package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.cyclecount.service.CycleCountPlanService;
import com.positivity.inventory.internal.cyclecount.service.CycleCountScheduleService;
import com.positivity.inventory.internal.cyclecount.service.CycleCountTaskGenerationService;
import com.positivity.inventory.internal.location.service.InventoryLocationService;
import com.positivity.inventory.internal.receiving.service.AsnService;
import com.positivity.inventory.internal.receiving.service.ReturnService;
import com.positivity.inventory.internal.replenishment.service.ReplenishmentService;
import com.positivity.inventory.internal.reservation.service.BackorderService;
import com.positivity.inventory.internal.reservation.service.ShortageResolutionService;
import com.positivity.inventory.internal.rollup.service.LocationInventoryRollupService;
import com.positivity.inventory.internal.scrap.service.ScrapService;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.internal.service.InventoryAvailabilityService;
import com.positivity.inventory.internal.service.InventoryLeadTimeService;
import com.positivity.inventory.internal.service.LocationInventoryInquiryService;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Web-slice proof of the controller-side location-scope gates rolled out in #1872 (ADR-0061 §3),
 * table-driven over every gated operation: in reach passes, out of reach is {@code 403
 * LOCATION_SCOPE_DENIED} with the service untouched, and both a pre-rollout token (no
 * {@code X-Loc-*} claims) and an ALL-scoped caller behave exactly as before.
 *
 * <p>Service-side gates (the narrowed lists and their by-id siblings) are proven in the service
 * unit tests; {@link ServiceDenial} shows their denial reaches the client through the same
 * {@code LOCATION_SCOPE_DENIED} envelope rather than the module's generic {@code FORBIDDEN}.
 * {@link LocationScopeAutoConfiguration} is imported because a {@code @WebMvcTest} slice does not
 * load library auto-configuration on its own.
 */
@WebMvcTest({
    CycleCountPlanController.class,
    CycleCountScheduleController.class,
    InventoryAvailabilityController.class,
    InventoryLocationDeactivationController.class,
    LocationInventoryInquiryController.class,
    LocationInventoryRollupController.class,
    ReplenishmentController.class,
    ReturnController.class,
    ScrapController.class,
    ShortageController.class,
    BackorderController.class,
    AsnController.class
})
@Import({TestSecurityConfig.class, LocationScopeAutoConfiguration.class})
@ActiveProfiles("test")
@DisplayName("pos-inventory location-scope gates (#1872)")
@SuppressWarnings({"java:S6813", "java:S1192"})
class InventoryLocationScopeControllerTest {

    /** The location every gated request names. */
    private static final UUID SHOP = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a30");
    /** The site above SHOP: assigning it puts SHOP in reach. */
    private static final UUID SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a31");
    /** A site elsewhere in the tree: assigning it leaves SHOP out of reach. */
    private static final UUID OTHER_SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a32");

    private static final UUID SOME_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a40");

    private static final LocationAncestorResolver RESOLVER = locationId ->
            SHOP.equals(locationId) ? new AncestorSets(Set.of(SHOP, SITE), Set.of(SHOP, SITE)) : AncestorSets.EMPTY;

    private static final List<String> PERMISSIONS = List.of(
            InventoryPermissionRegistry.CYCLE_COUNT_INITIATE,
            InventoryPermissionRegistry.AVAILABILITY_READ,
            InventoryPermissionRegistry.LOCATION_ADMIN,
            InventoryPermissionRegistry.INVENTORY_VIEW,
            InventoryPermissionRegistry.REPLENISHMENT_MANAGE,
            InventoryPermissionRegistry.RETURN_WRITE,
            InventoryPermissionRegistry.SCRAP_CREATE,
            InventoryPermissionRegistry.SHORTAGE_VIEW,
            InventoryPermissionRegistry.SHORTAGE_RESOLVE,
            InventoryPermissionRegistry.GOODS_RECEIPT_CREATE);

    /** One gated operation: the permission its gate uses and the request naming {@link #SHOP}. */
    record GateCase(
            String operation,
            String permission,
            int expectedStatus,
            Function<UUID, MockHttpServletRequestBuilder> request) {
        @Override
        public String toString() {
            return operation;
        }
    }

    static Stream<GateCase> gates() {
        return Stream.of(
                new GateCase(
                        "AsnController.createGoodsReceipt",
                        InventoryPermissionRegistry.GOODS_RECEIPT_CREATE,
                        201,
                        loc -> json(post("/v1/inventory/goods-receipts"), """
                                {"poId":"%s","locationId":"%s",
                                 "lines":[{"poLineId":"%s","sku":"SKU-1","quantityReceived":1,"unitCostMinor":100}]}
                                """.formatted(SOME_ID, loc, SOME_ID))),
                new GateCase(
                        "CycleCountPlanController.createPlan",
                        InventoryPermissionRegistry.CYCLE_COUNT_INITIATE,
                        201,
                        loc -> json(post("/v1/inventory/cycleCountPlans"), """
                                {"locationId":"%s","zoneIds":["%s"],"planName":"Weekly","scheduledDate":"2099-01-15"}
                                """.formatted(loc, SOME_ID))),
                new GateCase(
                        "CycleCountScheduleController.createSchedule",
                        InventoryPermissionRegistry.CYCLE_COUNT_INITIATE,
                        201,
                        loc -> json(post("/v1/inventory/cycleCountSchedules"), """
                                {"locationId":"%s","frequencyDays":30,"nextDueDate":"2099-01-15"}
                                """.formatted(loc))),
                new GateCase(
                        "InventoryAvailabilityController.queryLeadTime (locationId)",
                        InventoryPermissionRegistry.AVAILABILITY_READ,
                        200,
                        loc -> get("/v1/inventory/availability/lead-time")
                                .param("productId", SOME_ID.toString())
                                .param("locationId", loc.toString())),
                new GateCase(
                        "InventoryAvailabilityController.queryLeadTime (storageLocationId)",
                        InventoryPermissionRegistry.AVAILABILITY_READ,
                        200,
                        loc -> get("/v1/inventory/availability/lead-time")
                                .param("productId", SOME_ID.toString())
                                .param("storageLocationId", loc.toString())),
                new GateCase(
                        "InventoryLocationDeactivationController.deactivate",
                        InventoryPermissionRegistry.LOCATION_ADMIN,
                        200,
                        loc -> post("/v1/inventory/locations/{id}/deactivate", loc)),
                new GateCase(
                        "InventoryLocationDeactivationController.deactivate (destinationLocationId)",
                        InventoryPermissionRegistry.LOCATION_ADMIN,
                        200,
                        loc -> json(
                                post("/v1/inventory/locations/{id}/deactivate", loc),
                                "{\"destinationLocationId\":\"" + loc + "\"}")),
                new GateCase(
                        "LocationInventoryInquiryController.getLocationInventory",
                        InventoryPermissionRegistry.INVENTORY_VIEW,
                        200,
                        loc -> get("/v1/inventory/locations/{id}/inventory-inquiry", loc)),
                new GateCase(
                        "LocationInventoryInquiryController.listLocationInventoryItems",
                        InventoryPermissionRegistry.INVENTORY_VIEW,
                        200,
                        loc -> get("/v1/inventory/locations/{id}/inventory-items", loc)),
                new GateCase(
                        "LocationInventoryRollupController.getLocationInventoryRollup",
                        InventoryPermissionRegistry.INVENTORY_VIEW,
                        200,
                        loc -> get("/v1/inventory/locations/{id}/inventory-rollup", loc)),
                new GateCase(
                        "ReplenishmentController.createReplenishmentPolicy",
                        InventoryPermissionRegistry.REPLENISHMENT_MANAGE,
                        201,
                        loc -> json(post("/v1/inventory/replenishment/policies"), """
                                {"locationId":"%s","itemSKU":"SKU-1","minimumQuantity":1,"maximumQuantity":5}
                                """.formatted(loc))),
                new GateCase(
                        "ReturnController.submitToStock",
                        InventoryPermissionRegistry.RETURN_WRITE,
                        202,
                        loc -> json(
                                post("/v1/inventory/returns/submit-to-stock"), """
                                {"workorderId":"%s","lines":[{"itemId":"%s","quantity":1,"reasonCode":"DEFECTIVE",
                                 "locationId":"%s"}]}
                                """.formatted(SOME_ID, SOME_ID, loc))),
                new GateCase(
                        "ScrapController.createScrap",
                        InventoryPermissionRegistry.SCRAP_CREATE,
                        201,
                        loc -> json(post("/v1/inventory/scraps"), """
                                {"stockItemId":"SKU-1","quantity":1,"locationId":"%s","reasonCode":"DAMAGED"}
                                """.formatted(loc))),
                new GateCase(
                        "ShortageController.listShortageOptions",
                        InventoryPermissionRegistry.SHORTAGE_VIEW,
                        200,
                        loc -> get("/v1/inventory/shortage/options")
                                .param("allocationId", SOME_ID.toString())
                                .param("sku", "SKU-1")
                                .param("shortQuantity", "1")
                                .param("locationId", loc.toString())),
                new GateCase(
                        "ShortageController.resolveShortage (locationId)",
                        InventoryPermissionRegistry.SHORTAGE_RESOLVE,
                        200,
                        loc -> json(post("/v1/inventory/shortage/resolve"), """
                                {"idempotencyKey":"k-1","allocationId":"%s","optionType":"BACKORDER","sku":"SKU-1",
                                 "shortQuantity":1,"locationId":"%s"}
                                """.formatted(SOME_ID, loc))),
                new GateCase(
                        "ShortageController.resolveShortage (sourceLocationId)",
                        InventoryPermissionRegistry.SHORTAGE_RESOLVE,
                        200,
                        loc -> json(post("/v1/inventory/shortage/resolve"), """
                                {"idempotencyKey":"k-2","allocationId":"%s","optionType":"TRANSFER_IN","sku":"SKU-1",
                                 "shortQuantity":1,"sourceLocationId":"%s"}
                                """.formatted(SOME_ID, loc))));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    Clock clock;

    @MockitoBean
    CycleCountPlanService cycleCountPlanService;

    @MockitoBean
    CycleCountTaskGenerationService cycleCountTaskGenerationService;

    @MockitoBean
    CycleCountScheduleService cycleCountScheduleService;

    @MockitoBean
    InventoryAvailabilityService inventoryAvailabilityService;

    @MockitoBean
    InventoryLeadTimeService inventoryLeadTimeService;

    @MockitoBean
    InventoryLocationService inventoryLocationService;

    @MockitoBean
    LocationInventoryInquiryService locationInventoryInquiryService;

    @MockitoBean
    LocationInventoryRollupService locationInventoryRollupService;

    @MockitoBean
    ReplenishmentService replenishmentService;

    @MockitoBean
    ReturnService returnService;

    @MockitoBean
    ScrapService scrapService;

    @MockitoBean
    ShortageResolutionService shortageResolutionService;

    @MockitoBean
    BackorderService backorderService;

    @MockitoBean
    AsnService asnService;

    @BeforeEach
    void stubClock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-09-07T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private void verifyNoServiceCalled() {
        verifyNoInteractions(
                cycleCountPlanService,
                cycleCountTaskGenerationService,
                cycleCountScheduleService,
                inventoryAvailabilityService,
                inventoryLeadTimeService,
                inventoryLocationService,
                locationInventoryInquiryService,
                locationInventoryRollupService,
                replenishmentService,
                returnService,
                scrapService,
                shortageResolutionService,
                backorderService,
                asnService);
    }

    /** A post-rollout caller holding every gated permission, with the given scope in the details. */
    private static RequestPostProcessor caller(LocationScope scope) {
        var token = new UsernamePasswordAuthenticationToken(
                "manager",
                null,
                PERMISSIONS.stream().map(SimpleGrantedAuthority::new).toList());
        token.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                "manager",
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                scope));
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

    @ParameterizedTest(name = "{0}: location in reach passes")
    @MethodSource("gates")
    void inReach_passes(GateCase gate) throws Exception {
        mockMvc.perform(gate.request().apply(SHOP).with(caller(scopedTo(gate.permission(), SITE))))
                .andExpect(status().is(gate.expectedStatus()));
    }

    @ParameterizedTest(name = "{0}: location out of reach is 403 LOCATION_SCOPE_DENIED, service never called")
    @MethodSource("gates")
    void outOfReach_deniedBeforeService(GateCase gate) throws Exception {
        mockMvc.perform(gate.request().apply(SHOP).with(caller(scopedTo(gate.permission(), OTHER_SITE))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                .andExpect(jsonPath("$.status").value(403));

        verifyNoServiceCalled();
    }

    @ParameterizedTest(name = "{0}: pre-rollout token (no loc_* claims) is unchanged")
    @MethodSource("gates")
    void preRolloutToken_unchanged(GateCase gate) throws Exception {
        mockMvc.perform(gate.request().apply(SHOP).header("X-Authorities", String.join(",", PERMISSIONS)))
                .andExpect(status().is(gate.expectedStatus()));
    }

    @ParameterizedTest(name = "{0}: ALL-scoped caller (permission in neither bitset) is unchanged")
    @MethodSource("gates")
    void globalCaller_unchanged(GateCase gate) throws Exception {
        mockMvc.perform(gate.request().apply(SHOP).with(caller(globalReach(OTHER_SITE))))
                .andExpect(status().is(gate.expectedStatus()));
    }

    @Nested
    @DisplayName("optional location absent: nothing to gate")
    class OptionalAbsent {

        @Test
        @DisplayName("listShortageOptions without locationId passes a scoped caller")
        void shortageOptionsWithoutLocation() throws Exception {
            mockMvc.perform(get("/v1/inventory/shortage/options")
                            .param("allocationId", SOME_ID.toString())
                            .param("sku", "SKU-1")
                            .param("shortQuantity", "1")
                            .with(caller(scopedTo(InventoryPermissionRegistry.SHORTAGE_VIEW, OTHER_SITE))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("queryLeadTime without either location passes a scoped caller")
        void leadTimeWithoutLocation() throws Exception {
            mockMvc.perform(get("/v1/inventory/availability/lead-time")
                            .param("productId", SOME_ID.toString())
                            .with(caller(scopedTo(InventoryPermissionRegistry.AVAILABILITY_READ, OTHER_SITE))))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("service-side denial (narrowed lists and their by-id siblings)")
    class ServiceDenial {

        @Test
        @DisplayName("getBackorder: the service's scope denial renders as 403 LOCATION_SCOPE_DENIED")
        void byIdSiblingDenial() throws Exception {
            when(backorderService.getBackorder(SOME_ID))
                    .thenThrow(new LocationScopeDeniedException(
                            InventoryPermissionRegistry.SHORTAGE_VIEW, SHOP.toString()));

            mockMvc.perform(get("/v1/inventory/backorders/{id}", SOME_ID)
                            .with(caller(scopedTo(InventoryPermissionRegistry.SHORTAGE_VIEW, OTHER_SITE))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SHOP.toString()))));
        }

        @Test
        @DisplayName("queryAvailabilityBySku: a denied locationId renders the same envelope")
        void availabilityDenial() throws Exception {
            when(inventoryAvailabilityService.queryAvailability(any(), any(), any(), any(), any()))
                    .thenThrow(new LocationScopeDeniedException(
                            InventoryPermissionRegistry.AVAILABILITY_READ, SHOP.toString()));

            mockMvc.perform(get("/v1/inventory/availability/by-sku")
                            .param("productSku", "SKU-1")
                            .param("locationId", SHOP.toString())
                            .with(caller(scopedTo(InventoryPermissionRegistry.AVAILABILITY_READ, OTHER_SITE))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
            mockMvc.perform(get("/v1/inventory/availability")
                            .param("sku", "SKU-1")
                            .param("locationId", SHOP.toString())
                            .with(caller(scopedTo(InventoryPermissionRegistry.AVAILABILITY_READ, OTHER_SITE))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
        }

        @Test
        @DisplayName("listBackorders: a denied locationId filter renders the same envelope")
        void narrowedListFilterDenial() throws Exception {
            when(backorderService.listBackorders(any(), any(), any(), any(), any()))
                    .thenThrow(new LocationScopeDeniedException(
                            InventoryPermissionRegistry.SHORTAGE_VIEW, SHOP.toString()));

            mockMvc.perform(get("/v1/inventory/backorders")
                            .param("locationId", SHOP.toString())
                            .with(caller(scopedTo(InventoryPermissionRegistry.SHORTAGE_VIEW, OTHER_SITE))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
        }
    }
}
