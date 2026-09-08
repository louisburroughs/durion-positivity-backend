package com.positivity.location.internal.controller;

import static com.positivity.location.config.LocationScopeTestSupport.SITE_IN_REACH;
import static com.positivity.location.config.LocationScopeTestSupport.SITE_OUT_OF_REACH;
import static com.positivity.location.config.LocationScopeTestSupport.UNKNOWN_LOCATION;
import static com.positivity.location.config.LocationScopeTestSupport.as;
import static com.positivity.location.config.LocationScopeTestSupport.clearCaller;
import static com.positivity.location.config.LocationScopeTestSupport.globalWithClaims;
import static com.positivity.location.config.LocationScopeTestSupport.preRollout;
import static com.positivity.location.config.LocationScopeTestSupport.scopedOn;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.config.LocationScopeTestSupport;
import com.positivity.location.internal.dto.LocationPatchRequest;
import com.positivity.location.internal.dto.LocationRequestDTO;
import com.positivity.location.internal.dto.LocationResponseDTO;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.LocationRosterService;
import com.positivity.location.internal.service.LocationService;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.Optional;
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
 * Controller-boundary proof for #1872 on {@link LocationController}: the location mutations
 * ({@code PUT}, {@code PATCH}, {@code DELETE /v1/locations/{locationId}}) apply the caller's
 * location scope (ADR-0061 §3) on top of {@code location:write}, after the existing 404.
 *
 * <p>All three advices that meet in production are in the slice — {@link LocationScopeAutoConfiguration}
 * (highest precedence), this module's {@link LocationGlobalExceptionHandler} and pos-web-common's
 * {@link WebCommonErrorAutoConfiguration} (lowest) — so the assertion that the 403 body carries
 * {@code LOCATION_SCOPE_DENIED} proves the module handler yields to the shared one.
 */
@WebMvcTest(LocationController.class)
@Import({
    LocationScopeAutoConfiguration.class,
    WebCommonErrorAutoConfiguration.class,
    LocationScopeTestSupport.SliceConfig.class
})
@ActiveProfiles("test")
class LocationControllerLocationScopeTest {

    private static final String URL = "/v1/locations/{locationId}";

    private static final String FULL_BODY = """
            {"name":"Downtown Service Center","code":"LOC-001","type":{"name":"STORE"},"active":true}
            """;

    private static final String PATCH_BODY = "{\"status\":\"INACTIVE\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocationService locationService;

    @MockitoBean
    private LocationRosterService locationRosterService;

    @AfterEach
    void tearDown() {
        clearCaller();
    }

    private static LocationResponseDTO location(UUID id) {
        return LocationResponseDTO.builder()
                .id(id)
                .name("Location " + id)
                .active(true)
                .build();
    }

    private void existing(UUID id) {
        when(locationService.getLocationByIdDto(id)).thenReturn(Optional.of(location(id)));
        when(locationService.updateLocation(eq(id), any(LocationRequestDTO.class)))
                .thenReturn(Optional.of(location(id)));
        when(locationService.patchLocation(eq(id), any(LocationPatchRequest.class)))
                .thenReturn(location(id));
    }

    private void missing(UUID id) {
        when(locationService.getLocationByIdDto(id)).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("PUT /v1/locations/{locationId}")
    class UpdateLocation {

        @Test
        @DisplayName("scoped caller with the location in reach answers 200")
        void inReach() throws Exception {
            existing(SITE_IN_REACH);
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(put(URL, SITE_IN_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FULL_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(SITE_IN_REACH.toString()));

            verify(locationService).updateLocation(eq(SITE_IN_REACH), any(LocationRequestDTO.class));
        }

        @Test
        @DisplayName("scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void outOfReach() throws Exception {
            existing(SITE_OUT_OF_REACH);
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(put(URL, SITE_OUT_OF_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FULL_BODY)
                            .header(LocationGlobalExceptionHandler.X_CORRELATION_ID, "corr-scope-put"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.correlationId").value("corr-scope-put"))
                    .andExpect(jsonPath("$.message").value(not(containsString(SITE_OUT_OF_REACH.toString()))))
                    .andExpect(header().string(LocationGlobalExceptionHandler.X_CORRELATION_ID, "corr-scope-put"));

            verify(locationService, never()).updateLocation(any(), any());
        }

        @Test
        @DisplayName("an id the resolver does not know is out of reach, so a scoped caller gets 403")
        void unknownIdIsOutOfReachForAScopedCaller() throws Exception {
            missing(UNKNOWN_LOCATION);
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(put(URL, UNKNOWN_LOCATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FULL_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(locationService, never()).updateLocation(any(), any());
        }

        @Test
        @DisplayName("a location inside the caller's reach that does not exist still answers 404")
        void missingLocationInReachIs404() throws Exception {
            missing(SITE_IN_REACH);
            // Mockito already answers Optional.empty() for an unstubbed Optional method, so this
            // is redundant; it is spelled out because the 404 otherwise depends on a default that
            // is easy to misread as a null.
            when(locationService.updateLocation(eq(SITE_IN_REACH), any(LocationRequestDTO.class)))
                    .thenReturn(Optional.empty());
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(put(URL, SITE_IN_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FULL_BODY))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("pre-rollout token without loc_* claims keeps today's behaviour: 200 for any location")
        void preRolloutUnchanged() throws Exception {
            existing(SITE_OUT_OF_REACH);
            as(preRollout(LocationPermissions.WRITE));

            mockMvc.perform(put(URL, SITE_OUT_OF_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FULL_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("caller whose location:write is global answers 200 outside its nodes")
        void globalGrantNotLocationChecked() throws Exception {
            existing(SITE_OUT_OF_REACH);
            as(globalWithClaims(LocationPermissions.WRITE));

            mockMvc.perform(put(URL, SITE_OUT_OF_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FULL_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a locationId that is not a UUID answers 400 for every caller")
        void malformedIdIs400() throws Exception {
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(put(URL, "not-a-uuid")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FULL_BODY))
                    .andExpect(status().isBadRequest());

            verify(locationService, never()).getLocationByIdDto(any());
        }
    }

    @Nested
    @DisplayName("PATCH /v1/locations/{locationId}")
    class PatchLocation {

        @Test
        @DisplayName("scoped caller with the location in reach answers 200")
        void inReach() throws Exception {
            existing(SITE_IN_REACH);
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(patch(URL, SITE_IN_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PATCH_BODY))
                    .andExpect(status().isOk());

            verify(locationService).patchLocation(eq(SITE_IN_REACH), any(LocationPatchRequest.class));
        }

        @Test
        @DisplayName("scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void outOfReach() throws Exception {
            existing(SITE_OUT_OF_REACH);
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(patch(URL, SITE_OUT_OF_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PATCH_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(locationService, never()).patchLocation(any(), any());
        }

        @Test
        @DisplayName("an id the resolver does not know is out of reach, so a scoped caller gets 403")
        void unknownIdIsOutOfReachForAScopedCaller() throws Exception {
            missing(UNKNOWN_LOCATION);
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(patch(URL, UNKNOWN_LOCATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PATCH_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(locationService, never()).patchLocation(any(), any());
        }

        @Test
        @DisplayName("a location inside the caller's reach that does not exist still answers the 404 ProblemDetail")
        void missingLocationInReachIs404() throws Exception {
            missing(SITE_IN_REACH);
            when(locationService.patchLocation(eq(SITE_IN_REACH), any(LocationPatchRequest.class)))
                    .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(patch(URL, SITE_IN_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PATCH_BODY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("pre-rollout token patches any location as before")
        void preRolloutUnchanged() throws Exception {
            existing(SITE_OUT_OF_REACH);
            as(preRollout(LocationPermissions.WRITE));

            mockMvc.perform(patch(URL, SITE_OUT_OF_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PATCH_BODY))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("DELETE /v1/locations/{locationId}")
    class DeleteLocation {

        @Test
        @DisplayName("scoped caller with the location in reach answers 204")
        void inReach() throws Exception {
            existing(SITE_IN_REACH);
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(delete(URL, SITE_IN_REACH)).andExpect(status().isNoContent());

            verify(locationService).deleteLocation(SITE_IN_REACH);
        }

        @Test
        @DisplayName("scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void outOfReach() throws Exception {
            existing(SITE_OUT_OF_REACH);
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(delete(URL, SITE_OUT_OF_REACH))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(header().exists(LocationGlobalExceptionHandler.X_CORRELATION_ID));

            verify(locationService, never()).deleteLocation(any());
        }

        @Test
        @DisplayName("a missing location answers 404 before any scope check")
        void missingLocationIs404First() throws Exception {
            missing(UNKNOWN_LOCATION);
            as(scopedOn(LocationPermissions.WRITE));

            mockMvc.perform(delete(URL, UNKNOWN_LOCATION)).andExpect(status().isNotFound());

            verify(locationService, never()).deleteLocation(any());
        }

        @Test
        @DisplayName("pre-rollout token deletes any location as before")
        void preRolloutUnchanged() throws Exception {
            existing(SITE_OUT_OF_REACH);
            as(preRollout(LocationPermissions.WRITE));

            mockMvc.perform(delete(URL, SITE_OUT_OF_REACH)).andExpect(status().isNoContent());
        }
    }
}
