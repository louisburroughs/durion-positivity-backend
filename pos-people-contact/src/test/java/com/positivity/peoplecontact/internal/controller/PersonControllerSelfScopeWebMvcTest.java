package com.positivity.peoplecontact.internal.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.peoplecontact.config.TestSecurityConfig;
import com.positivity.peoplecontact.internal.dto.Person;
import com.positivity.peoplecontact.internal.service.PersonService;
import com.positivity.peoplecontact.internal.service.UserPersonTranslationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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
 * Pins the authorization boundary {@code GET /v1/people/me} moved to in issue #1895.
 *
 * <p>The endpoint returns the caller's own person record and nothing else, so it is gated on
 * {@code people:self:view}, seeded to the operational staff roles. It used to require
 * {@code people-contact:person:view} — the same permission that opens the whole identity
 * directory, and one granted to the admin role only, so every other role was refused its own
 * record. The directory reads keep that permission, which is the half of the boundary the last
 * test here holds in place.
 */
@WebMvcTest(PersonController.class)
@Import({TestSecurityConfig.class, PersonControllerSelfScopeWebMvcTest.FixedClockConfig.class})
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100"})
@DisplayName("PersonController — self-scoped read")
class PersonControllerSelfScopeWebMvcTest {

    private static final String AUTHORITIES = "X-Authorities";

    /** The self-scope permission the operational staff roles hold, and nothing else. */
    private static final String SELF_VIEW_ONLY = "people:self:view";

    /** An authority a technician plausibly holds, and that this controller never asks for. */
    private static final String UNRELATED_AUTHORITY = "workorder:workorder:view";

    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c03");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PersonService personService;

    @MockitoBean
    UserPersonTranslationService userPersonTranslationService;

    @Test
    @DisplayName("the self permission alone reads the caller's own person record")
    void getCurrentPerson_needsOnlyTheSelfPermission() throws Exception {
        Person person = new Person();
        person.setId(PERSON_ID);
        when(userPersonTranslationService.getPersonUuidForCurrentUser()).thenReturn(PERSON_ID);
        when(personService.getPersonById(PERSON_ID)).thenReturn(Optional.of(person));

        mockMvc.perform(get("/v1/people/me").header(AUTHORITIES, SELF_VIEW_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PERSON_ID.toString()));
    }

    /**
     * Authentication alone is not the gate: the customer-facing roles authenticate against this
     * platform holding no people permission, and must not reach the staff identity directory.
     */
    @Test
    @DisplayName("a caller without the self permission is still refused")
    void getCurrentPerson_refusesACallerWithoutTheSelfPermission() throws Exception {
        mockMvc.perform(get("/v1/people/me").header(AUTHORITIES, UNRELATED_AUTHORITY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the self permission does not open the identity directory")
    void listPeople_isNotOpenedByTheSelfPermission() throws Exception {
        mockMvc.perform(get("/v1/people").header(AUTHORITIES, SELF_VIEW_ONLY)).andExpect(status().isForbidden());
    }

    /**
     * A real fixed {@code Clock}: {@link PeopleExceptionHandler} takes one as a constructor
     * argument and reads it on every error response, so the slice cannot start without it.
     */
    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);
        }
    }
}
