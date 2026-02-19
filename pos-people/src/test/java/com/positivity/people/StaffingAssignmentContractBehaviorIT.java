package com.positivity.people;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CAP-119 / Issue #86 — Person-to-Location Staffing Assignment Contract Tests.
 *
 * These tests are written RED-first: they will fail (404 No Handler Found)
 * until the staffing assignment endpoints are implemented in pos-people.
 *
 * Contract:
 * domains/people/.business-rules/BACKEND_CONTRACT_GUIDE.md
 * Section: "CAP-119: Person-to-Location Staffing Assignments"
 *
 * Business rules under test:
 * - A person can hold multiple assignments but only ONE primary at any time
 * - Creating a new primary must atomically end the previous primary
 * - Overlapping assignments for same personId+locationId+role are rejected
 * (409)
 * - locationId must reference an ACTIVE location in pos-location service
 * - All writes emit domain events
 *
 * RED state: All tests expect 2xx/4xx but will receive 404 until endpoints
 * exist.
 */
@DisplayName("CAP-119 Staffing Assignment ContractBehaviorIT")
class StaffingAssignmentContractBehaviorIT extends BaseIntegrationTest {

    private static final String STAFFING_BASE = "/v1/people/staffing/assignments";
    private static final String VALID_PERSON_ID = "018e1c9f-0000-7000-8000-100000000001";
    private static final String VALID_LOCATION_ID = "018e1c9f-0000-7000-8000-200000000001";
    private static final String VALID_LOCATION_ID_2 = "018e1c9f-0000-7000-8000-200000000002";

    // ========== HAPPY PATH ==========

    @Test
    @DisplayName("CP-119-100: Create primary assignment returns 201 and ends previous primary automatically")
    void CP_119_100_createPrimaryAssignment_returns201_and_ends_previousPrimary() throws Exception {
        // First primary assignment
        String firstPrimary = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "TECHNICIAN",
                    "isPrimary": true,
                    "effectiveFrom": "2026-01-01"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID);

        var firstResult = mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstPrimary)))
                // RED: 404 until endpoints exist; after GREEN: expect 201
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignmentId").exists())
                .andExpect(jsonPath("$.isPrimary").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        String firstAssignmentId = objectMapper
                .readTree(firstResult.getResponse().getContentAsString())
                .get("assignmentId").asText();

        // Second (new) primary assignment — must auto-end the first
        String secondPrimary = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "SHOP_MANAGER",
                    "isPrimary": true,
                    "effectiveFrom": "2026-06-01"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID_2);

        mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondPrimary)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isPrimary").value(true));

        // Verify first assignment was auto-ended (effectiveTo set to 2026-05-31)
        mockMvc.perform(withAuth(
                get(STAFFING_BASE + "/" + firstAssignmentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.effectiveTo").value("2026-05-31"));
    }

    @Test
    @DisplayName("CP-119-101: Create non-primary assignment does not affect existing primary")
    void CP_119_101_createNonPrimaryAssignment_does_not_affect_existingPrimary() throws Exception {
        // Create initial primary
        String primary = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "SERVICE_ADVISOR",
                    "isPrimary": true,
                    "effectiveFrom": "2026-01-01"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID);

        var primaryResult = mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(primary)))
                .andExpect(status().isCreated())
                .andReturn();

        String primaryId = objectMapper
                .readTree(primaryResult.getResponse().getContentAsString())
                .get("assignmentId").asText();

        // Create non-primary assignment at same location
        String nonPrimary = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "TECHNICIAN",
                    "isPrimary": false,
                    "effectiveFrom": "2026-03-01"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID_2);

        mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nonPrimary)))
                .andExpect(status().isCreated());

        // Primary must be untouched
        mockMvc.perform(withAuth(get(STAFFING_BASE + "/" + primaryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.isPrimary").value(true))
                .andExpect(jsonPath("$.effectiveTo").doesNotExist());
    }

    @Test
    @DisplayName("CP-119-102: GET /v1/people/staffing/assignments?personId={uuid} returns list")
    void CP_119_102_getAssignmentsByPersonId_returns_list() throws Exception {
        mockMvc.perform(withAuth(
                get(STAFFING_BASE)
                        .param("personId", VALID_PERSON_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("CP-119-103: GET /v1/people/staffing/assignments/{id} returns assignment by ID")
    void CP_119_103_getAssignmentById_returns200() throws Exception {
        // Create one
        String createPayload = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "RECEPTIONIST",
                    "isPrimary": false,
                    "effectiveFrom": "2026-02-01"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID);

        var createResult = mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload)))
                .andExpect(status().isCreated())
                .andReturn();

        String assignmentId = objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .get("assignmentId").asText();

        mockMvc.perform(withAuth(get(STAFFING_BASE + "/" + assignmentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentId").value(assignmentId))
                .andExpect(jsonPath("$.personId").value(VALID_PERSON_ID))
                .andExpect(jsonPath("$.locationId").value(VALID_LOCATION_ID))
                .andExpect(jsonPath("$.role").value("RECEPTIONIST"))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-02-01"));
    }

    @Test
    @DisplayName("CP-119-104: PUT /v1/people/staffing/assignments/{id} updates assignment and returns 200")
    void CP_119_104_updateAssignment_returns200() throws Exception {
        String createPayload = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "RECEPTIONIST",
                    "isPrimary": false,
                    "effectiveFrom": "2026-02-01"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID);

        var createResult = mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload)))
                .andExpect(status().isCreated())
                .andReturn();

        String assignmentId = objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .get("assignmentId").asText();

        String updatePayload = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "SHOP_FOREMAN",
                    "isPrimary": true,
                    "effectiveFrom": "2026-03-01",
                    "effectiveTo": "2026-12-31"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID_2);

        mockMvc.perform(withAuth(
                put(STAFFING_BASE + "/" + assignmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentId").value(assignmentId))
                .andExpect(jsonPath("$.personId").value(VALID_PERSON_ID))
                .andExpect(jsonPath("$.locationId").value(VALID_LOCATION_ID_2))
                .andExpect(jsonPath("$.role").value("SHOP_FOREMAN"))
                .andExpect(jsonPath("$.isPrimary").value(true))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-03-01"))
                .andExpect(jsonPath("$.effectiveTo").value("2026-12-31"));
    }

    @Test
    @DisplayName("LC-119-102: Non-existent assignment PUT returns 404")
    void LC_119_102_updateNonExistent_returns404() throws Exception {
        String nonExistentId = "018e1c9f-dead-7000-8000-888888888888";
        String updatePayload = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "SHOP_FOREMAN",
                    "isPrimary": false,
                    "effectiveFrom": "2026-03-01"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID);

        mockMvc.perform(withAuth(
                put(STAFFING_BASE + "/" + nonExistentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload)))
                .andExpect(status().isNotFound());
    }

    // ========== VALIDATION / EDGE CASES ==========

    @Test
    @DisplayName("VE-119-100: Overlapping assignment for same personId+locationId+role returns 409")
    void VE_119_100_overlappingAssignment_returns409() throws Exception {
        String first = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "DETAILER",
                    "isPrimary": false,
                    "effectiveFrom": "2026-01-01",
                    "effectiveTo": "2026-12-31"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID);

        mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first)))
                .andExpect(status().isCreated());

        // Overlapping date range: same person+location+role
        String overlapping = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "DETAILER",
                    "isPrimary": false,
                    "effectiveFrom": "2026-06-01",
                    "effectiveTo": "2027-03-31"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID);

        mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overlapping)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("VE-119-104: Updating assignment to overlapping range returns 409")
    void VE_119_104_updateOverlappingAssignment_returns409() throws Exception {
        String first = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "DETAILER",
                    "isPrimary": false,
                    "effectiveFrom": "2026-01-01",
                    "effectiveTo": "2026-06-30"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID);

        mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first)))
                .andExpect(status().isCreated());

        String second = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "DETAILER",
                    "isPrimary": false,
                    "effectiveFrom": "2026-07-01",
                    "effectiveTo": "2026-12-31"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID);

        var secondResult = mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(second)))
                .andExpect(status().isCreated())
                .andReturn();

        String secondAssignmentId = objectMapper
                .readTree(secondResult.getResponse().getContentAsString())
                .get("assignmentId").asText();

        String overlappingUpdate = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "DETAILER",
                    "isPrimary": false,
                    "effectiveFrom": "2026-06-15",
                    "effectiveTo": "2026-12-31"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID);

        mockMvc.perform(withAuth(
                put(STAFFING_BASE + "/" + secondAssignmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overlappingUpdate)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("VE-119-105: Create assignment with effectiveTo before effectiveFrom returns 400")
    void VE_119_105_createInvalidDateWindow_returns400() throws Exception {
        String payload = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "TECHNICIAN",
                    "isPrimary": false,
                    "effectiveFrom": "2026-06-10",
                    "effectiveTo": "2026-06-01"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID);

        mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VE-119-106: Update assignment with effectiveTo before effectiveFrom returns 400")
    void VE_119_106_updateInvalidDateWindow_returns400() throws Exception {
        String createPayload = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "TECHNICIAN",
                    "isPrimary": false,
                    "effectiveFrom": "2026-02-01"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID);

        var createResult = mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload)))
                .andExpect(status().isCreated())
                .andReturn();

        String assignmentId = objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .get("assignmentId").asText();

        String invalidUpdatePayload = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "TECHNICIAN",
                    "isPrimary": false,
                    "effectiveFrom": "2026-06-10",
                    "effectiveTo": "2026-06-01"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID);

        mockMvc.perform(withAuth(
                put(STAFFING_BASE + "/" + assignmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidUpdatePayload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VE-119-101: Non-existent locationId returns 404 or 400")
    void VE_119_101_nonExistentLocation_returns4xx() throws Exception {
        String nonExistentLocationId = "018e1c9f-dead-7000-8000-000000000000";
        String payload = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "TECHNICIAN",
                    "isPrimary": false,
                    "effectiveFrom": "2026-02-01"
                }
                """.formatted(VALID_PERSON_ID, nonExistentLocationId);

        // Expect 400 (inactive) or 404 (not found) — location service will reject
        mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("VE-119-102: Missing required personId returns 400")
    void VE_119_102_missingPersonId_returns400() throws Exception {
        String payload = """
                {
                    "locationId": "%s",
                    "role": "TECHNICIAN",
                    "isPrimary": false,
                    "effectiveFrom": "2026-02-01"
                }
                """.formatted(VALID_LOCATION_ID);

        mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VE-119-103: Missing required locationId returns 400")
    void VE_119_103_missingLocationId_returns400() throws Exception {
        String payload = """
                {
                    "personId": "%s",
                    "role": "TECHNICIAN",
                    "isPrimary": false,
                    "effectiveFrom": "2026-02-01"
                }
                """.formatted(VALID_PERSON_ID);

        mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isBadRequest());
    }

    // ========== LIFECYCLE ==========

    @Test
    @DisplayName("LC-119-100: DELETE /v1/people/staffing/assignments/{id} transitions assignment to ENDED")
    void LC_119_100_endAssignment_transitions_status_to_ENDED() throws Exception {
        // Create an assignment
        String createPayload = """
                {
                    "personId": "%s",
                    "locationId": "%s",
                    "role": "DRIVER",
                    "isPrimary": false,
                    "effectiveFrom": "2026-01-01"
                }
                """.formatted(VALID_PERSON_ID, VALID_LOCATION_ID);

        var createResult = mockMvc.perform(withAuth(
                post(STAFFING_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload)))
                .andExpect(status().isCreated())
                .andReturn();

        String assignmentId = objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .get("assignmentId").asText();

        // End the assignment
        mockMvc.perform(withAuth(delete(STAFFING_BASE + "/" + assignmentId)))
                .andExpect(status().isNoContent());

        // Verify it's ended (not deleted — must be preserved for audit)
        mockMvc.perform(withAuth(get(STAFFING_BASE + "/" + assignmentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"));
    }

    @Test
    @DisplayName("LC-119-101: Non-existent assignment DELETE returns 404")
    void LC_119_101_deleteNonExistent_returns404() throws Exception {
        String nonExistentId = "018e1c9f-dead-7000-8000-999999999999";
        mockMvc.perform(withAuth(delete(STAFFING_BASE + "/" + nonExistentId)))
                .andExpect(status().isNotFound());
    }
}
