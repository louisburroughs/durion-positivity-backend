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
 */
@WebMvcTest(PeopleAvailabilityController.class)
@Import({TestSecurityConfig.class, PeopleAvailabilityControllerTest.FixedClockConfig.class})
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class PeopleAvailabilityControllerTest {

    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c02");
    private static final UUID LOCATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

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

        mockMvc.perform(get("/v1/people/me/primary-location").header("X-Authorities", "people:availability:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.locationName").value("Downtown Store"))
                .andExpect(jsonPath("$.defaulted").value(false));
    }

    @Test
    void getCurrentUserPrimaryLocation_omitsLocationNameWhenReplicaHasNoMatchingRow() throws Exception {
        when(peopleAvailabilityService.resolveCurrentUserPrimaryLocation())
                .thenReturn(new PrimaryLocationResolution(LOCATION_ID, null, true));

        mockMvc.perform(get("/v1/people/me/primary-location").header("X-Authorities", "people:availability:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.locationName").doesNotExist())
                .andExpect(jsonPath("$.defaulted").value(true));
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
