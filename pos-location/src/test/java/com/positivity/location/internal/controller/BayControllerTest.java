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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.config.LocationScopeTestSupport;
import com.positivity.location.internal.dto.BayPatchRequest;
import com.positivity.location.internal.dto.BayRequest;
import com.positivity.location.internal.dto.BayResponse;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.BayService;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-boundary proof for #1872 on {@link BayController}: every operation under
 * {@code /v1/locations/{locationId}/bays} applies the caller's location scope (ADR-0061 §3) to the
 * path {@code locationId}, on top of {@code location:bay:read} / {@code location:bay:manage}, before
 * the service is called. Also pins the strict UUID parse: a malformed id is a 400 for every caller.
 *
 * <p>The slice carries the three advices that meet in production (shared scope handler, this
 * module's ProblemDetail handler, pos-web-common's catch-all) so the {@code LOCATION_SCOPE_DENIED}
 * assertion proves precedence, not just presence.
 */
@WebMvcTest(BayController.class)
@Import({
    LocationScopeAutoConfiguration.class,
    WebCommonErrorAutoConfiguration.class,
    LocationScopeTestSupport.SliceConfig.class
})
@ActiveProfiles("test")
class BayControllerTest {

    private static final String BAYS_URL = "/v1/locations/{locationId}/bays";
    private static final String BAY_URL = "/v1/locations/{locationId}/bays/{bayId}";
    private static final UUID BAY_ID = UUID.fromString("019200bb-0000-7000-8000-000000000b01");

    private static final String CREATE_BODY = """
            {"name":"Bay A1","bayType":"GENERAL_SERVICE","capacity":{"maxConcurrentVehicles":2}}
            """;

    private static final String PATCH_BODY = "{\"status\":\"OUT_OF_SERVICE\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BayService bayService;

    @AfterEach
    void tearDown() {
        clearCaller();
    }

    private static BayResponse bayAt(UUID locationId) {
        return BayResponse.builder()
                .id(BAY_ID)
                .locationId(locationId)
                .name("Bay A1")
                .bayType("GENERAL_SERVICE")
                .status("ACTIVE")
                .maxConcurrentVehicles(2)
                .build();
    }

    private void stubBaysAt(UUID locationId) {
        when(bayService.listBays(eq(locationId), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(bayAt(locationId))));
        when(bayService.getBay(locationId, BAY_ID)).thenReturn(bayAt(locationId));
        when(bayService.createBay(eq(locationId), any(BayRequest.class))).thenReturn(bayAt(locationId));
        when(bayService.patchBay(eq(locationId), eq(BAY_ID), any(BayPatchRequest.class)))
                .thenReturn(bayAt(locationId));
        when(bayService.deleteBay(locationId, BAY_ID)).thenReturn(true);
    }

    @Nested
    @DisplayName("GET /v1/locations/{locationId}/bays (location:bay:read)")
    class ListBays {

        @Test
        @DisplayName("scoped caller with the location in reach answers 200")
        void inReach() throws Exception {
            stubBaysAt(SITE_IN_REACH);
            as(scopedOn(LocationPermissions.BAY_READ));

            mockMvc.perform(get(BAYS_URL, SITE_IN_REACH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].locationId").value(SITE_IN_REACH.toString()));
        }

        @Test
        @DisplayName("scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void outOfReach() throws Exception {
            as(scopedOn(LocationPermissions.BAY_READ));

            mockMvc.perform(get(BAYS_URL, SITE_OUT_OF_REACH)
                            .header(LocationGlobalExceptionHandler.X_CORRELATION_ID, "corr-scope-bays"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.correlationId").value("corr-scope-bays"))
                    .andExpect(jsonPath("$.message").value(not(containsString(SITE_OUT_OF_REACH.toString()))))
                    .andExpect(header().string(LocationGlobalExceptionHandler.X_CORRELATION_ID, "corr-scope-bays"));

            verify(bayService, never()).listBays(any(), any(), any(), any());
        }

        @Test
        @DisplayName("scoped caller naming a location the resolver does not hold answers 403 (fail closed)")
        void unknownLocationDenies() throws Exception {
            as(scopedOn(LocationPermissions.BAY_READ));

            mockMvc.perform(get(BAYS_URL, UNKNOWN_LOCATION))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
        }

        @Test
        @DisplayName("pre-rollout token without loc_* claims keeps today's behaviour: 200 for any location")
        void preRolloutUnchanged() throws Exception {
            stubBaysAt(SITE_OUT_OF_REACH);
            as(preRollout(LocationPermissions.BAY_READ));

            mockMvc.perform(get(BAYS_URL, SITE_OUT_OF_REACH)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("caller whose location:bay:read is global answers 200 outside its nodes")
        void globalGrantNotLocationChecked() throws Exception {
            stubBaysAt(SITE_OUT_OF_REACH);
            as(globalWithClaims(LocationPermissions.BAY_READ));

            mockMvc.perform(get(BAYS_URL, SITE_OUT_OF_REACH)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("a locationId that is not a UUID answers 400 for a scoped caller")
        void malformedIdIs400ForScopedCaller() throws Exception {
            as(scopedOn(LocationPermissions.BAY_READ));

            mockMvc.perform(get(BAYS_URL, "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(bayService, never()).listBays(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a locationId that is not a UUID answers 400 for a pre-rollout caller too")
        void malformedIdIs400ForUnscopedCaller() throws Exception {
            as(preRollout(LocationPermissions.BAY_READ));

            mockMvc.perform(get(BAYS_URL, "not-a-uuid")).andExpect(status().isBadRequest());

            verify(bayService, never()).listBays(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a location the service does not know is still 404 for an in-reach scoped caller")
        void serviceNotFoundStays404() throws Exception {
            when(bayService.listBays(eq(SITE_IN_REACH), any(), any(), any(Pageable.class)))
                    .thenThrow(new ResourceNotFoundException("Location not found"));
            as(scopedOn(LocationPermissions.BAY_READ));

            mockMvc.perform(get(BAYS_URL, SITE_IN_REACH)).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /v1/locations/{locationId}/bays/{bayId} (location:bay:read)")
    class GetBay {

        @Test
        @DisplayName("scoped caller with the location in reach answers 200")
        void inReach() throws Exception {
            stubBaysAt(SITE_IN_REACH);
            as(scopedOn(LocationPermissions.BAY_READ));

            mockMvc.perform(get(BAY_URL, SITE_IN_REACH, BAY_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(BAY_ID.toString()));
        }

        @Test
        @DisplayName("scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void outOfReach() throws Exception {
            as(scopedOn(LocationPermissions.BAY_READ));

            mockMvc.perform(get(BAY_URL, SITE_OUT_OF_REACH, BAY_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(bayService, never()).getBay(any(), any());
        }

        @Test
        @DisplayName("a bayId that is not a UUID answers 400")
        void malformedBayIdIs400() throws Exception {
            as(scopedOn(LocationPermissions.BAY_READ));

            mockMvc.perform(get(BAY_URL, SITE_IN_REACH, "not-a-uuid")).andExpect(status().isBadRequest());

            verify(bayService, never()).getBay(any(), any());
        }
    }

    @Nested
    @DisplayName("POST /v1/locations/{locationId}/bays (location:bay:manage)")
    class CreateBay {

        @Test
        @DisplayName("scoped caller with the location in reach answers 201")
        void inReach() throws Exception {
            stubBaysAt(SITE_IN_REACH);
            as(scopedOn(LocationPermissions.BAY_MANAGE));

            mockMvc.perform(post(BAYS_URL, SITE_IN_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isCreated());

            verify(bayService).createBay(eq(SITE_IN_REACH), any(BayRequest.class));
        }

        @Test
        @DisplayName("scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void outOfReach() throws Exception {
            as(scopedOn(LocationPermissions.BAY_MANAGE));

            mockMvc.perform(post(BAYS_URL, SITE_OUT_OF_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(bayService, never()).createBay(any(), any());
        }

        @Test
        @DisplayName("pre-rollout token creates at any location as before")
        void preRolloutUnchanged() throws Exception {
            stubBaysAt(SITE_OUT_OF_REACH);
            as(preRollout(LocationPermissions.BAY_MANAGE));

            mockMvc.perform(post(BAYS_URL, SITE_OUT_OF_REACH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("PATCH /v1/locations/{locationId}/bays/{bayId} (location:bay:manage)")
    class PatchBay {

        @Test
        @DisplayName("scoped caller with the location in reach answers 200")
        void inReach() throws Exception {
            stubBaysAt(SITE_IN_REACH);
            as(scopedOn(LocationPermissions.BAY_MANAGE));

            mockMvc.perform(patch(BAY_URL, SITE_IN_REACH, BAY_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PATCH_BODY))
                    .andExpect(status().isOk());

            verify(bayService).patchBay(eq(SITE_IN_REACH), eq(BAY_ID), any(BayPatchRequest.class));
        }

        @Test
        @DisplayName("scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void outOfReach() throws Exception {
            as(scopedOn(LocationPermissions.BAY_MANAGE));

            mockMvc.perform(patch(BAY_URL, SITE_OUT_OF_REACH, BAY_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PATCH_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(bayService, never()).patchBay(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("DELETE /v1/locations/{locationId}/bays/{bayId} (location:bay:manage)")
    class DeleteBay {

        @Test
        @DisplayName("scoped caller with the location in reach answers 204")
        void inReach() throws Exception {
            stubBaysAt(SITE_IN_REACH);
            as(scopedOn(LocationPermissions.BAY_MANAGE));

            mockMvc.perform(delete(BAY_URL, SITE_IN_REACH, BAY_ID)).andExpect(status().isNoContent());

            verify(bayService).deleteBay(SITE_IN_REACH, BAY_ID);
        }

        @Test
        @DisplayName("scoped caller with the location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void outOfReach() throws Exception {
            as(scopedOn(LocationPermissions.BAY_MANAGE));

            mockMvc.perform(delete(BAY_URL, SITE_OUT_OF_REACH, BAY_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(bayService, never()).deleteBay(any(), any());
        }

        @Test
        @DisplayName("pre-rollout token deletes at any location as before")
        void preRolloutUnchanged() throws Exception {
            stubBaysAt(SITE_OUT_OF_REACH);
            as(preRollout(LocationPermissions.BAY_MANAGE));

            mockMvc.perform(delete(BAY_URL, SITE_OUT_OF_REACH, BAY_ID)).andExpect(status().isNoContent());
        }
    }
}
