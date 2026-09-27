package com.positivity.location.internal.controller;

import static com.positivity.location.config.LocationScopeTestSupport.SITE_IN_REACH;
import static com.positivity.location.config.LocationScopeTestSupport.SITE_OUT_OF_REACH;
import static com.positivity.location.config.LocationScopeTestSupport.as;
import static com.positivity.location.config.LocationScopeTestSupport.clearCaller;
import static com.positivity.location.config.LocationScopeTestSupport.preRollout;
import static com.positivity.location.config.LocationScopeTestSupport.scopedOn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.config.LocationScopeTestSupport;
import com.positivity.location.internal.dto.EligibleMobileUnitResponse;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.MobileUnitService;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-boundary proof for {@code findEligibleMobileUnits}' location-scope gate (PR #2278
 * MEDIUM): {@code baseLocationId} names the shop whose units are offered and is gated exactly like
 * {@link MobileUnitController#listMobileUnits}'s own {@code baseLocationId} filter
 * (ADR-0061, {@code pos-location/location-scope.yaml}).
 */
@WebMvcTest(MobileUnitEligibilityController.class)
@Import({
    LocationScopeAutoConfiguration.class,
    WebCommonErrorAutoConfiguration.class,
    LocationScopeTestSupport.SliceConfig.class
})
@ActiveProfiles("test")
class MobileUnitEligibilityControllerTest {

    private static final String ELIGIBLE_URL = "/v1/mobile-units:eligible";
    private static final UUID UNIT_ID = UUID.fromString("019200aa-0000-7000-8000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MobileUnitService mobileUnitService;

    @AfterEach
    void tearDown() {
        clearCaller();
    }

    private static EligibleMobileUnitResponse eligibleUnit(UUID baseLocationId) {
        return EligibleMobileUnitResponse.builder()
                .id(UNIT_ID)
                .name("Van 7")
                .baseLocationId(baseLocationId)
                .priority(1)
                .build();
    }

    @Test
    @DisplayName("PR #2278 MEDIUM - a scoped caller naming a baseLocationId outside their reach is 403; "
            + "the service is not called")
    void deniesOutOfReachBaseLocation() throws Exception {
        as(scopedOn(LocationPermissions.MOBILE_UNIT_READ));

        mockMvc.perform(get(ELIGIBLE_URL)
                        .param("postalCode", "78701")
                        .param("countryCode", "US")
                        .param("at", Instant.parse("2026-09-08T12:00:00Z").toString())
                        .param("baseLocationId", SITE_OUT_OF_REACH.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

        verify(mobileUnitService, never())
                .findEligibleMobileUnits(anyString(), anyString(), any(Instant.class), any(), anyList());
    }

    @Test
    @DisplayName("PR #2278 MEDIUM - a scoped caller naming a baseLocationId in reach reaches the service")
    void allowsInReachBaseLocation() throws Exception {
        when(mobileUnitService.findEligibleMobileUnits(anyString(), anyString(), any(Instant.class), any(), anyList()))
                .thenReturn(List.of(eligibleUnit(SITE_IN_REACH)));
        as(scopedOn(LocationPermissions.MOBILE_UNIT_READ));

        mockMvc.perform(get(ELIGIBLE_URL)
                        .param("postalCode", "78701")
                        .param("countryCode", "US")
                        .param("at", Instant.parse("2026-09-08T12:00:00Z").toString())
                        .param("baseLocationId", SITE_IN_REACH.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(UNIT_ID.toString()));
    }

    @Test
    @DisplayName("PR #2278 MEDIUM - a pre-rollout caller with no baseLocationId still gets the service's "
            + "own 400, never a scope denial")
    void missingBaseLocationIdIsNotAScopeDenial() throws Exception {
        as(preRollout(LocationPermissions.MOBILE_UNIT_READ));

        mockMvc.perform(get(ELIGIBLE_URL)
                        .param("postalCode", "78701")
                        .param("countryCode", "US")
                        .param("at", Instant.parse("2026-09-08T12:00:00Z").toString()))
                .andExpect(status().isOk());

        verify(mobileUnitService)
                .findEligibleMobileUnits(anyString(), anyString(), any(Instant.class), any(), anyList());
    }
}
