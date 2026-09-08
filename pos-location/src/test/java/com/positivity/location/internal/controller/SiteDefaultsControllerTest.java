package com.positivity.location.internal.controller;

import static com.positivity.location.config.LocationScopeTestSupport.SITE_IN_REACH;
import static com.positivity.location.config.LocationScopeTestSupport.SITE_OUT_OF_REACH;
import static com.positivity.location.config.LocationScopeTestSupport.as;
import static com.positivity.location.config.LocationScopeTestSupport.clearCaller;
import static com.positivity.location.config.LocationScopeTestSupport.globalWithClaims;
import static com.positivity.location.config.LocationScopeTestSupport.preRollout;
import static com.positivity.location.config.LocationScopeTestSupport.scopedOn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.config.LocationScopeTestSupport;
import com.positivity.location.internal.dto.SiteDefaultsRequest;
import com.positivity.location.internal.dto.SiteDefaultsResponse;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.SiteDefaultsService;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller-boundary proof for #1872 on {@link SiteDefaultsController}: both operations under
 * {@code /v1/locations/{locationId}/defaults} apply the caller's location scope (ADR-0061 §3) to
 * the path {@code locationId} — {@code location:write} for the upsert, {@code location:read} for
 * the read — before the service is called.
 */
@WebMvcTest(SiteDefaultsController.class)
@Import({
    LocationScopeAutoConfiguration.class,
    WebCommonErrorAutoConfiguration.class,
    LocationScopeTestSupport.SliceConfig.class
})
@ActiveProfiles("test")
class SiteDefaultsControllerTest {

    private static final String URL = "/v1/locations/{locationId}/defaults";
    private static final UUID STAGING = UUID.fromString("019200bb-0000-7000-8000-000000000d01");
    private static final UUID QUARANTINE = UUID.fromString("019200bb-0000-7000-8000-000000000d02");

    private static final String BODY =
            "{\"defaultStagingLocationId\":\"" + STAGING + "\",\"defaultQuarantineLocationId\":\"" + QUARANTINE + "\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SiteDefaultsService siteDefaultsService;

    @AfterEach
    void tearDown() {
        clearCaller();
    }

    private void stubDefaultsAt(UUID siteId) {
        SiteDefaultsResponse response = SiteDefaultsResponse.builder()
                .siteId(siteId)
                .defaultStagingLocationId(STAGING)
                .defaultQuarantineLocationId(QUARANTINE)
                .build();
        when(siteDefaultsService.configureDefaults(eq(siteId), any(SiteDefaultsRequest.class)))
                .thenReturn(response);
        when(siteDefaultsService.getDefaults(siteId)).thenReturn(response);
    }

    @Nested
    @DisplayName("PUT /v1/locations/{locationId}/defaults (location:write)")
    class ConfigureDefaults {

        @Test
        @DisplayName("scoped caller with the site in reach answers 200")
        void inReach() throws Exception {
            stubDefaultsAt(SITE_IN_REACH);
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(put(URL, SITE_IN_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.siteId").value(SITE_IN_REACH.toString()));

            verify(siteDefaultsService).configureDefaults(eq(SITE_IN_REACH), any(SiteDefaultsRequest.class));
        }

        @Test
        @DisplayName("scoped caller with the site out of reach answers 403 LOCATION_SCOPE_DENIED")
        void outOfReach() throws Exception {
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(put(URL, SITE_OUT_OF_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY)
                            .header(LocationGlobalExceptionHandler.X_CORRELATION_ID, "corr-scope-defaults"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.correlationId").value("corr-scope-defaults"))
                    .andExpect(header().string(LocationGlobalExceptionHandler.X_CORRELATION_ID, "corr-scope-defaults"));

            verify(siteDefaultsService, never()).configureDefaults(any(), any());
        }

        @Test
        @DisplayName("pre-rollout token configures any site as before")
        void preRolloutUnchanged() throws Exception {
            stubDefaultsAt(SITE_OUT_OF_REACH);
            as(preRollout(LocationPermissions.WRITE));

            mockMvc.perform(put(URL, SITE_OUT_OF_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an in-reach site the service does not know is still 404")
        void serviceNotFoundStays404() throws Exception {
            when(siteDefaultsService.configureDefaults(eq(SITE_IN_REACH), any(SiteDefaultsRequest.class)))
                    .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "SITE_NOT_FOUND"));
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(put(URL, SITE_IN_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("SITE_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("GET /v1/locations/{locationId}/defaults (location:read)")
    class GetDefaults {

        @Test
        @DisplayName("scoped caller with the site in reach answers 200")
        void inReach() throws Exception {
            stubDefaultsAt(SITE_IN_REACH);
            as(scopedOn(LocationPermissions.READ));

            mockMvc.perform(get(URL, SITE_IN_REACH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.defaultStagingLocationId").value(STAGING.toString()));
        }

        @Test
        @DisplayName("scoped caller with the site out of reach answers 403 LOCATION_SCOPE_DENIED")
        void outOfReach() throws Exception {
            as(scopedOn(LocationPermissions.READ));

            mockMvc.perform(get(URL, SITE_OUT_OF_REACH))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(siteDefaultsService, never()).getDefaults(any());
        }

        @Test
        @DisplayName("caller whose location:read is global answers 200 outside its nodes")
        void globalGrantNotLocationChecked() throws Exception {
            stubDefaultsAt(SITE_OUT_OF_REACH);
            as(globalWithClaims(LocationPermissions.READ));

            mockMvc.perform(get(URL, SITE_OUT_OF_REACH)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("pre-rollout token reads any site as before")
        void preRolloutUnchanged() throws Exception {
            stubDefaultsAt(SITE_OUT_OF_REACH);
            as(preRollout(LocationPermissions.READ));

            mockMvc.perform(get(URL, SITE_OUT_OF_REACH)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("a locationId that is not a UUID answers 400 for every caller")
        void malformedIdIs400() throws Exception {
            as(scopedOn(LocationPermissions.READ));

            mockMvc.perform(get(URL, "not-a-uuid")).andExpect(status().isBadRequest());

            verify(siteDefaultsService, never()).getDefaults(any());
        }
    }
}
