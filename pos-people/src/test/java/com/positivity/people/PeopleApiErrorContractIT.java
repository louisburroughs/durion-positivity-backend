package com.positivity.people;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #820 — staffing / location-assignment query variants must map to 4xx, not 500.
 *
 * <p>Spring MVC signals an unmatched path with {@link
 * org.springframework.web.servlet.resource.NoResourceFoundException} and a non-UUID path
 * variable with {@link
 * org.springframework.web.method.annotation.MethodArgumentTypeMismatchException}. Both used
 * to fall through to PeopleExceptionHandler's Exception catch-all and surface as 500
 * Internal Server Error for every probed endpoint shape.
 */
@DisplayName("Issue #820 People API error-mapping contract")
class PeopleApiErrorContractIT extends BaseContractIntegrationTest {

    private static final String PERSON_ID = "583fa3b3-d1bf-a40d-8e21-8cd54424d5d0";

    @Test
    @DisplayName("GET /v1/people/me (no such endpoint; 'me' is not a UUID) returns 400, not 500")
    void meReturnsBadRequest() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/me")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid value 'me' for parameter 'personId'"));
    }

    @Test
    @DisplayName("GET /v1/people/me/locations (unknown path) returns 404, not 500")
    void meLocationsReturnsNotFound() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/me/locations")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No endpoint GET /v1/people/me/locations"));
    }

    @Test
    @DisplayName("GET /v1/people/{id}/locations (unknown path) returns 404, not 500")
    void personLocationsReturnsNotFound() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/" + PERSON_ID + "/locations"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/people/{id}/primary-location (unknown path) returns 404, not 500")
    void personPrimaryLocationReturnsNotFound() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/" + PERSON_ID + "/primary-location")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/people/{id}/staffing/assignments (unknown path) returns 404, not 500")
    void personStaffingAssignmentsReturnsNotFound() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/" + PERSON_ID + "/staffing/assignments")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/people/staffing/assignments without personId returns 400, not 500")
    void staffingAssignmentsMissingPersonIdReturnsBadRequest() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/staffing/assignments")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Missing required parameter 'personId'"));
    }

    @Test
    @DisplayName("GET /v1/people/staffing/assignments with malformed personId returns 400, not 500")
    void staffingAssignmentsMalformedPersonIdReturnsBadRequest() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/staffing/assignments").param("personId", "not-a-uuid")))
                .andExpect(status().isBadRequest());
    }
}
