package com.positivity.people;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #820 — routing/binding failures must map to 4xx, not 500.
 *
 * <p>Spring MVC signals an unmatched path with {@link
 * org.springframework.web.servlet.resource.NoResourceFoundException}, a non-UUID
 * parameter with {@link
 * org.springframework.web.method.annotation.MethodArgumentTypeMismatchException}, and a
 * missing query parameter with {@link
 * org.springframework.web.bind.MissingServletRequestParameterException}. All three used to
 * fall through to PeopleExceptionHandler's Exception catch-all and surface as 500 Internal
 * Server Error. Current-user and person-scoped location reads that used to 404/500 as
 * unknown paths are now real endpoints, covered by {@link PersonLocationReadContractIT}.
 */
@DisplayName("Issue #820 People API error-mapping contract")
class PeopleApiErrorContractIT extends BaseContractIntegrationTest {

    private static final String PERSON_ID = "583fa3b3-d1bf-a40d-8e21-8cd54424d5d0";

    @Test
    @DisplayName("Unknown path returns 404, not 500")
    void unknownPathReturnsNotFound() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/" + PERSON_ID + "/no-such-resource")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No endpoint GET /v1/people/" + PERSON_ID + "/no-such-resource"));
    }

    @Test
    @DisplayName("Non-UUID path variable returns 400, not 500")
    void malformedPathVariableReturnsBadRequest() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid value 'not-a-uuid' for parameter 'personId'"));
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
