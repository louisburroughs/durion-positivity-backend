package com.positivity.catalog.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.config.TestSecurityConfig;
import com.positivity.catalog.internal.config.ProductFactReplayService;
import com.positivity.catalog.internal.dto.LocationPriceOverrideResponseDto;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.CatalogService;
import com.positivity.catalog.internal.service.LocationPriceOverrideService;
import com.positivity.catalog.internal.service.ProductCodeLookupService;
import com.positivity.catalog.internal.service.ProductDetailService;
import com.positivity.catalog.internal.service.ProductLifecycleService;
import com.positivity.catalog.internal.service.ProductMasterDataService;
import com.positivity.catalog.internal.service.ProductSearchService;
import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-boundary proof for #1885 (ADR-0061 §3) on the one pos-catalog mutation that names a
 * location: {@code createLocationPriceOverride} gates on the body's {@code locationId}.
 *
 * <p>The read endpoints in this module stay unscoped by decision — a location there selects a
 * price book and the response carries no location-private data — so this is the module's whole
 * enforcement surface; the decisions are recorded in {@code location-scope.yaml}.
 *
 * <p>The {@link LocationScope} rides in the authentication details map exactly where
 * {@code GatewayAuthoritiesFilter} puts it, with a map-backed {@link LocationAncestorResolver}
 * standing in for the {@code ext_location} replica. {@link LocationScopeAutoConfiguration} is
 * imported so the {@code LOCATION_SCOPE_DENIED} advice wins over the module's generic handler,
 * as in production.
 */
@WebMvcTest(ProductController.class)
@Import({TestSecurityConfig.class, CatalogExceptionHandler.class, LocationScopeAutoConfiguration.class})
@ActiveProfiles("test")
@DisplayName("POST /v1/products/pricing/location-overrides — location scope (#1885)")
@SuppressWarnings("java:S6813")
class ProductLocationScopeControllerTest {

    private static final String URL = "/v1/products/pricing/location-overrides";

    /** The node the scoped caller is assigned: a region above {@link #SHOP_A}. */
    private static final UUID REGION_NODE = UUID.fromString("019200bb-0000-7000-8000-00000000a000");

    private static final UUID SHOP_A = UUID.fromString("019200bb-0000-7000-8000-00000000000a");
    private static final UUID SHOP_B = UUID.fromString("019200bb-0000-7000-8000-00000000000b");
    private static final UUID PRODUCT_ID = UUID.fromString("019200bb-0000-7000-8000-000000000101");
    private static final UUID ACTOR_ID = UUID.fromString("019200bb-0000-7000-8000-000000000102");

    /** Replica stand-in: SHOP_A sits under REGION_NODE on the OTHER dimension; SHOP_B does not. */
    private static final Map<UUID, AncestorSets> REPLICA = Map.of(
            SHOP_A, new AncestorSets(Set.of(SHOP_A), Set.of(SHOP_A, REGION_NODE)),
            SHOP_B, new AncestorSets(Set.of(SHOP_B), Set.of(SHOP_B)));

    private static final LocationAncestorResolver RESOLVER =
            locationId -> REPLICA.getOrDefault(locationId, AncestorSets.EMPTY);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private java.time.Clock clock;

    @MockitoBean
    private org.springframework.cache.CacheManager cacheManager;

    @MockitoBean
    private LocationPriceOverrideService locationPriceOverrideService;

    @MockitoBean
    private CatalogService catalogService;

    @MockitoBean
    private ProductCodeLookupService productCodeLookupService;

    @MockitoBean
    private ProductDetailService productDetailService;

    @MockitoBean
    private ProductLifecycleService productLifecycleService;

    @MockitoBean
    private ProductMasterDataService productMasterDataService;

    @MockitoBean
    private ProductSearchService productSearchService;

    @MockitoBean
    private ProductFactReplayService productFactReplayService;

    @BeforeEach
    void setUpClock() {
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
    }

    // ------------------------------------------------------------------ caller shapes

    /** A post-rollout token whose write permission is OTHER-scoped to {@link #REGION_NODE}. */
    private static Authentication scopedCaller() {
        return as(LocationScope.of(
                Set.of(),
                Set.of(CatalogPermissions.LOCATION_PRICE_OVERRIDE_WRITE),
                Optional.of(Set.of(REGION_NODE)),
                true,
                RESOLVER));
    }

    /** A post-rollout token carrying the scope bitset but no assigned nodes: reach is nothing. */
    private static Authentication scopedCallerWithoutNodes() {
        return as(LocationScope.of(
                Set.of(), Set.of(CatalogPermissions.LOCATION_PRICE_OVERRIDE_WRITE), Optional.empty(), true, RESOLVER));
    }

    /** A post-rollout token that carries claims but whose write permission is global. */
    private static Authentication globalCaller() {
        return as(LocationScope.of(Set.of(), Set.of(), Optional.of(Set.of(REGION_NODE)), true, RESOLVER));
    }

    /** A pre-rollout token: no {@code loc_*} claims at all, so no scope detail is attached. */
    private static Authentication preRolloutCaller() {
        return as(null);
    }

    private static Authentication as(LocationScope scope) {
        var token = new UsernamePasswordAuthenticationToken(
                "scope-test-user",
                null,
                List.of(new SimpleGrantedAuthority(CatalogPermissions.LOCATION_PRICE_OVERRIDE_WRITE)));
        token.setDetails(
                scope == null
                        ? Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "scope-test-user")
                        : Map.of(
                                GatewaySecurityConstants.DETAIL_USERNAME,
                                "scope-test-user",
                                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                                scope));
        return token;
    }

    private static String body(UUID locationId) {
        return """
                {"locationId":"%s","productId":"%s","basePrice":100.00,"cost":60.00,
                 "overridePrice":92.50,"createdByUserId":"%s"}
                """.formatted(locationId, PRODUCT_ID, ACTOR_ID);
    }

    private void stubCreated() {
        var response = new LocationPriceOverrideResponseDto();
        when(locationPriceOverrideService.createOverride(any())).thenReturn(response);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("scoped caller creating at a location in reach answers 201")
    void inReachIsAllowed() throws Exception {
        stubCreated();

        mockMvc.perform(post(URL)
                        .with(authentication(scopedCaller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SHOP_A)))
                .andExpect(status().isCreated());

        verify(locationPriceOverrideService).createOverride(any());
    }

    @Test
    @DisplayName("scoped caller creating at a location out of reach answers 403 LOCATION_SCOPE_DENIED, nothing written")
    void outOfReachIsDenied() throws Exception {
        mockMvc.perform(post(URL)
                        .with(authentication(scopedCaller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SHOP_B)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                .andExpect(jsonPath("$.status").value(403));

        verify(locationPriceOverrideService, never()).createOverride(any());
    }

    @Test
    @DisplayName("a location the replica does not hold is denied for a scoped caller — fail closed")
    void unknownLocationIsDenied() throws Exception {
        mockMvc.perform(post(URL)
                        .with(authentication(scopedCaller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.fromString("019200bb-0000-7000-8000-0000000000ff"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

        verify(locationPriceOverrideService, never()).createOverride(any());
    }

    @Test
    @DisplayName("scope bitset present with no assigned nodes reaches nothing and is denied")
    void scopedWithoutNodesIsDenied() throws Exception {
        mockMvc.perform(post(URL)
                        .with(authentication(scopedCallerWithoutNodes()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SHOP_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

        verify(locationPriceOverrideService, never()).createOverride(any());
    }

    @Test
    @DisplayName("caller whose grant is global answers 201 for a location outside its nodes")
    void globalGrantIsNotLocationChecked() throws Exception {
        stubCreated();

        mockMvc.perform(post(URL)
                        .with(authentication(globalCaller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SHOP_B)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("pre-rollout token without loc_* claims keeps today's behaviour: 201 for any location")
    void preRolloutUnchanged() throws Exception {
        stubCreated();

        mockMvc.perform(post(URL)
                        .with(authentication(preRolloutCaller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SHOP_B)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a body missing locationId is a 400 for a scoped caller too — validation precedes scope")
    void validationPrecedesScope() throws Exception {
        mockMvc.perform(post(URL)
                        .with(authentication(scopedCaller()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"%s","basePrice":100.00,"overridePrice":92.50,
                                 "createdByUserId":"%s"}
                                """.formatted(PRODUCT_ID, ACTOR_ID)))
                .andExpect(status().isBadRequest());

        verify(locationPriceOverrideService, never()).createOverride(any());
    }
}
