package com.positivity.vehicle.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.vehicle.config.WebMvcTestSecurityConfig;
import com.positivity.vehicle.internal.dto.UpsertPreferencesRequest;
import com.positivity.vehicle.internal.entity.VehicleCarePreference;
import com.positivity.vehicle.internal.security.VehicleInventoryPermissions;
import com.positivity.vehicle.internal.service.VehiclePreferencesService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link VehiclePreferencesController}.
 *
 * <p>
 * The endpoint that matters here is the pair PUT/PATCH, because the two treat a
 * null {@code serviceIntervalMonths} as opposite instructions (#1175): on PUT it
 * <em>clears</em> the per-vehicle override so CRM falls back to its default, on
 * PATCH it <em>leaves the current value alone</em>. Both arrive over the wire as
 * an absent JSON field, so nothing downstream can tell them apart — the
 * distinction lives entirely in which verb was used, and it survives only as
 * long as the controller keeps passing the null through instead of defaulting or
 * dropping it. These tests capture the service argument rather than asserting on
 * the response, because that pass-through is the whole contract.
 */
@WebMvcTest(VehiclePreferencesController.class)
@Import(WebMvcTestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
@DisplayName("VehiclePreferencesController — web slice")
class VehiclePreferencesControllerWebMvcTest {

    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID PREFERENCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final String PATH = "/v1/vehicles/" + VEHICLE_ID + "/preferences";
    private static final String AUTH = "Authorization";
    private static final String BEARER = "Bearer test";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    VehiclePreferencesService preferencesService;

    private static VehicleCarePreference stored(Integer serviceIntervalMonths) {
        VehicleCarePreference preference = new VehicleCarePreference();
        preference.setId(PREFERENCE_ID);
        preference.setPreferences(Map.of("preferredOil", "5W-30"));
        preference.setServiceIntervalMonths(serviceIntervalMonths);
        return preference;
    }

    @Test
    @DisplayName("GET returns 404 when the vehicle has no preferences yet")
    void getMissingPreferencesIsNotFound() throws Exception {
        when(preferencesService.getPreferences(VEHICLE_ID)).thenReturn(Optional.empty());

        // A vehicle with no preferences is an ordinary state, not an error — but it must not
        // read as an empty preference set, which a 200 with a null body would.
        mockMvc.perform(get(PATH).header(AUTH, BEARER)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET returns the stored preferences flattened onto the response")
    void getReturnsStoredPreferences() throws Exception {
        when(preferencesService.getPreferences(VEHICLE_ID)).thenReturn(Optional.of(stored(6)));

        mockMvc.perform(get(PATH).header(AUTH, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PREFERENCE_ID.toString()))
                .andExpect(jsonPath("$.serviceIntervalMonths").value(6))
                .andExpect(jsonPath("$.preferences.preferredOil").value("5W-30"));
    }

    @Test
    @DisplayName("PUT forwards an omitted serviceIntervalMonths as null, which clears the override")
    void putForwardsOmittedIntervalAsNull() throws Exception {
        when(preferencesService.upsertPreferences(any())).thenReturn(stored(null));

        mockMvc.perform(put(PATH)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferences\":{\"preferredOil\":\"5W-30\"}}"))
                .andExpect(status().isOk());

        ArgumentCaptor<UpsertPreferencesRequest> captor = ArgumentCaptor.forClass(UpsertPreferencesRequest.class);
        verify(preferencesService).upsertPreferences(captor.capture());
        // Per #1175 a null here means "clear the per-vehicle override". If the controller ever
        // substituted a default, the caller would silently keep an interval they asked to drop.
        assertThat(captor.getValue().getServiceIntervalMonths()).isNull();
        assertThat(captor.getValue().getVehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(captor.getValue().getPreferences()).containsEntry("preferredOil", "5W-30");
    }

    @Test
    @DisplayName("PATCH forwards an omitted serviceIntervalMonths as null, which leaves it unchanged")
    void patchForwardsOmittedIntervalAsNull() throws Exception {
        when(preferencesService.mergePreferences(eq(VEHICLE_ID), any(), any(), any()))
                .thenReturn(stored(6));

        mockMvc.perform(patch(PATH)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partialPreferences\":{\"tireRotationInterval\":7500}}"))
                .andExpect(status().isOk());

        // Same null on the wire as the PUT above, opposite meaning. The verbs are the only
        // thing separating "clear it" from "don't touch it", which is why both are pinned.
        verify(preferencesService).mergePreferences(eq(VEHICLE_ID), any(), isNull(), isNull());
    }

    @Test
    @DisplayName("PATCH with no partialPreferences merges an empty map rather than failing")
    void patchWithoutPartialPreferencesMergesEmptyMap() throws Exception {
        when(preferencesService.mergePreferences(eq(VEHICLE_ID), any(), any(), any()))
                .thenReturn(stored(9));

        // A PATCH that only changes the interval is a legitimate request; the field is
        // optional, so the controller substitutes an empty map instead of dereferencing null.
        mockMvc.perform(patch(PATH)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceIntervalMonths\":9}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(preferencesService).mergePreferences(eq(VEHICLE_ID), captor.capture(), eq(9), isNull());
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("PUT rejects a body with no preferences map")
    void putWithoutPreferencesIsRejected() throws Exception {
        // preferences is @NotNull on the upsert DTO: a PUT is a full replacement, so an absent
        // map would otherwise be indistinguishable from "replace everything with nothing".
        mockMvc.perform(put(PATH)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceIntervalMonths\":6}"))
                .andExpect(status().isBadRequest());

        verify(preferencesService, never()).upsertPreferences(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 121})
    @DisplayName("PUT rejects a service interval outside 1..120 months")
    void putRejectsOutOfRangeInterval(int months) throws Exception {
        mockMvc.perform(put(PATH)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferences\":{},\"serviceIntervalMonths\":" + months + "}"))
                .andExpect(status().isBadRequest());

        verify(preferencesService, never()).upsertPreferences(any());
    }

    @Test
    @DisplayName("DELETE returns 204 even when the vehicle had no preferences")
    void deleteIsAlwaysNoContent() throws Exception {
        // The controller does not consult the service's outcome, so the documented 404 on this
        // endpoint is unreachable. Deleting is idempotent in practice, which is defensible —
        // pinned here so that a later change to return 404 is a deliberate contract change and
        // not an accident.
        mockMvc.perform(delete(PATH).header(AUTH, BEARER)).andExpect(status().isNoContent());

        verify(preferencesService).deletePreferences(VEHICLE_ID);
    }

    @Test
    @DisplayName("rejects an unauthenticated request")
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());

        verify(preferencesService, never()).getPreferences(any());
    }

    @Test
    @DisplayName("registry:view does not open preferences (Task-5, decisions k/l)")
    void registryViewAuthorityDoesNotOpenPreferences() throws Exception {
        // vehicle-inventory:registry:view is a plausible authority for the same operator to hold,
        // since it opens the registry record itself; preferences:manage is deliberately separate.
        mockMvc.perform(get(PATH)
                        .header(AUTH, BEARER)
                        .header("X-Authorities", VehicleInventoryPermissions.REGISTRY_VIEW))
                .andExpect(status().isForbidden());

        verify(preferencesService, never()).getPreferences(any());
    }
}
