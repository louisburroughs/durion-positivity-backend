package com.positivity.people.internal.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.dto.PrimaryLocationResolution;
import com.positivity.people.internal.service.PeopleAvailabilityService;
import com.positivity.people.internal.service.StaffingAssignmentService;
import com.positivity.people.internal.service.UserPersonTranslationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies that both primary-location endpoints serialize the denormalized {@code locationName}
 * alongside {@code locationId} (issue #1680), including when the replica has not caught up and
 * the service resolves a null name.
 *
 * <p>Also pins the authorization boundary the {@code /me} endpoints moved to in issue #1895: they
 * are self-scoped reads gated on {@code people:self:view}, which every staff role holds, while
 * the location roster read they used to share a permission with still demands
 * {@code people:availability:view}.
 */
@WebMvcTest(PeopleAvailabilityController.class)
@Import({TestSecurityConfig.class, PeopleAvailabilityControllerTest.FixedClockConfig.class})
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class PeopleAvailabilityControllerTest {

    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c02");
    private static final UUID LOCATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** The self-scope permission every staff role holds, and nothing else. */
    private static final String SELF_VIEW_ONLY = "people:self:view";

    /** An authority a technician plausibly holds, and that this controller never asks for. */
    private static final String UNRELATED_AUTHORITY = "workorder:workorder:view";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PeopleAvailabilityService peopleAvailabilityService;

    @MockitoBean
    StaffingAssignmentService staffingAssignmentService;

    @MockitoBean
    UserPersonTranslationService userPersonTranslationService;

    @Test
    void getCurrentUserPrimaryLocation_serializesLocationName() throws Exception {
        when(peopleAvailabilityService.resolveCurrentUserPrimaryLocation())
                .thenReturn(new PrimaryLocationResolution(LOCATION_ID, "Downtown Store", false));

        mockMvc.perform(get("/v1/people/me/primary-location").header("X-Authorities", SELF_VIEW_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.locationName").value("Downtown Store"))
                .andExpect(jsonPath("$.defaulted").value(false));
    }

    @Test
    void getCurrentUserPrimaryLocation_omitsLocationNameWhenReplicaHasNoMatchingRow() throws Exception {
        when(peopleAvailabilityService.resolveCurrentUserPrimaryLocation())
                .thenReturn(new PrimaryLocationResolution(LOCATION_ID, null, true));

        mockMvc.perform(get("/v1/people/me/primary-location").header("X-Authorities", SELF_VIEW_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.locationName").doesNotExist())
                .andExpect(jsonPath("$.defaulted").value(true));
    }

    /**
     * The permission {@code people:availability:view} is granted to three roles only, so before
     * issue #1895 a technician, advisor, accountant or parts clerk was refused their own primary
     * location with a 403 the published contract never allowed for. {@code people:self:view}
     * alone — no availability, no employee authority — must now be enough.
     */
    @Test
    void getCurrentUserPrimaryLocation_needsOnlyTheSelfPermission() throws Exception {
        when(peopleAvailabilityService.resolveCurrentUserPrimaryLocation())
                .thenReturn(new PrimaryLocationResolution(LOCATION_ID, "Downtown Store", false));

        mockMvc.perform(get("/v1/people/me/primary-location").header("X-Authorities", SELF_VIEW_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(LOCATION_ID.toString()));
    }

    @Test
    void getCurrentUserLocations_needsOnlyTheSelfPermission() throws Exception {
        when(userPersonTranslationService.getPersonUuidForCurrentUser()).thenReturn(PERSON_ID);
        when(staffingAssignmentService.findActiveByPersonId(PERSON_ID)).thenReturn(List.of());

        mockMvc.perform(get("/v1/people/me/locations").header("X-Authorities", SELF_VIEW_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    /**
     * Authentication alone is not the gate. The customer-facing roles authenticate against this
     * platform and hold no people permission at all; {@code people:self:view} is what keeps them
     * out of a staff endpoint, so a caller without it must still be refused.
     */
    @Test
    void getCurrentUserPrimaryLocation_refusesACallerWithoutTheSelfPermission() throws Exception {
        mockMvc.perform(get("/v1/people/me/primary-location").header("X-Authorities", UNRELATED_AUTHORITY))
                .andExpect(status().isForbidden());
    }

    /**
     * The counterweight to the two grant tests above: opening the self-scoped reads must not open
     * the roster read, which lists other people at a location and keeps its own permission.
     */
    @Test
    void getPeopleAvailability_isNotOpenedByTheSelfPermission() throws Exception {
        mockMvc.perform(get("/v1/people/availability").header("X-Authorities", SELF_VIEW_ONLY))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPersonPrimaryLocation_serializesLocationName() throws Exception {
        when(peopleAvailabilityService.resolvePrimaryLocationId(PERSON_ID))
                .thenReturn(new PrimaryLocationResolution(LOCATION_ID, "Downtown Store", false));

        mockMvc.perform(get("/v1/people/{personId}/primary-location", PERSON_ID)
                        .header("X-Authorities", "people:employee:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.locationName").value("Downtown Store"))
                .andExpect(jsonPath("$.defaulted").value(false));
    }

    @Test
    void getPersonPrimaryLocation_omitsLocationNameWhenReplicaHasNoMatchingRow() throws Exception {
        when(peopleAvailabilityService.resolvePrimaryLocationId(PERSON_ID))
                .thenReturn(new PrimaryLocationResolution(LOCATION_ID, null, false));

        mockMvc.perform(get("/v1/people/{personId}/primary-location", PERSON_ID)
                        .header("X-Authorities", "people:employee:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.locationName").doesNotExist());
    }

    /**
     * A real fixed {@code Clock}, not a mock. {@code PeopleExceptionHandler} reads it on every
     * error response ({@code Instant.now(clock)}), and an unstubbed mock returns {@code null} —
     * which made the advice itself throw, so the original exception surfaced as unhandled
     * (issue #1716). Fixed rather than {@code systemUTC} so timestamps stay deterministic.
     */
    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
