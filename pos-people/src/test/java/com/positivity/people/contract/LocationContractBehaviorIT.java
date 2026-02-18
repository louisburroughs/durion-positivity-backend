package com.positivity.people.contract;

import com.positivity.people.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("CAP-119 Location & Staffing ContractBehaviorIT")
class LocationContractBehaviorIT extends BaseIntegrationTest {

    @Test
    @DisplayName("CP-119-001: Create location happy path")
    void cp119001_createLocation_happyPath() throws Exception {
        mockMvc.perform(withAuth(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createLocationPayload("L-001", "Main Store"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("L-001"))
                .andExpect(jsonPath("$.displayName").value("Main Store"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("CP-119-002: Get location by ID")
    void cp119002_getLocationById() throws Exception {
        String response = mockMvc.perform(withAuth(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createLocationPayload("L-002", "Warehouse"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String locationId = objectMapper.readTree(response).get("locationId").asText();

        mockMvc.perform(withAuth(get("/v1/locations/{locationId}", locationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(locationId));
    }

    @Test
    @DisplayName("CP-119-003: Update location")
    void cp119003_updateLocation() throws Exception {
        String response = mockMvc.perform(withAuth(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createLocationPayload("L-003", "Original"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String locationId = objectMapper.readTree(response).get("locationId").asText();

        mockMvc.perform(withAuth(put("/v1/locations/{locationId}", locationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "displayName": "Updated Name",
                          "locationType": "OFFICE",
                          "timezone": "America/Chicago"
                        }
                        """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Updated Name"))
                .andExpect(jsonPath("$.locationType").value("OFFICE"));
    }

    @Test
    @DisplayName("CP-119-004: Delete location then GET is 404")
    void cp119004_deleteLocation() throws Exception {
        String response = mockMvc.perform(withAuth(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createLocationPayload("L-004", "To Delete"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String locationId = objectMapper.readTree(response).get("locationId").asText();

        mockMvc.perform(withAuth(delete("/v1/locations/{locationId}", locationId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(withAuth(get("/v1/locations/{locationId}", locationId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CP-119-005: List locations")
    void cp119005_listLocations() throws Exception {
        mockMvc.perform(withAuth(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createLocationPayload("L-005", "List A"))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createLocationPayload("L-006", "List B"))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(get("/v1/locations")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("VE-119-001: Create location missing code -> 400")
    void ve119001_createLocationMissingCode() throws Exception {
        mockMvc.perform(withAuth(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "displayName": "Invalid",
                          "locationType": "STORE",
                          "timezone": "America/New_York"
                        }
                        """)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VE-119-002: Create location duplicate code -> 409")
    void ve119002_duplicateLocationCode() throws Exception {
        mockMvc.perform(withAuth(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createLocationPayload("L-007", "Original"))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createLocationPayload("L-007", "Duplicate"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("CP-119-010: Assign staff to location")
    void cp119010_assignStaff() throws Exception {
        String locationId = createLocation("L-010", "Staffed Location");
        UUID personId = createPerson();

        mockMvc.perform(withAuth(post("/v1/locations/{locationId}/staff", locationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignStaffPayload(personId, "MECHANIC", true, "2026-01-01", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.personId").value(personId.toString()))
                .andExpect(jsonPath("$.isPrimary").value(true));
    }

    @Test
    @DisplayName("CP-119-011: Get staff for location")
    void cp119011_getStaff() throws Exception {
        String locationId = createLocation("L-011", "Staff Query Location");
        UUID personId = createPerson();

        mockMvc.perform(withAuth(post("/v1/locations/{locationId}/staff", locationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignStaffPayload(personId, "MANAGER", false, "2026-01-01", null))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(get("/v1/locations/{locationId}/staff", locationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].personId").value(personId.toString()));
    }

    @Test
    @DisplayName("CP-119-012: Remove staff from location")
    void cp119012_removeStaff() throws Exception {
        String locationId = createLocation("L-012", "Unassign Location");
        UUID personId = createPerson();

        mockMvc.perform(withAuth(post("/v1/locations/{locationId}/staff", locationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignStaffPayload(personId, "TECH", false, "2026-01-01", null))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(delete("/v1/locations/{locationId}/staff/{personId}", locationId, personId)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("VE-119-010: Assign person already assigned -> 409")
    void ve119010_assignDuplicate() throws Exception {
        String locationId = createLocation("L-013", "Conflict Location");
        UUID personId = createPerson();

        mockMvc.perform(withAuth(post("/v1/locations/{locationId}/staff", locationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignStaffPayload(personId, "TECH", false, "2026-01-01", "2026-12-31"))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(post("/v1/locations/{locationId}/staff", locationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignStaffPayload(personId, "TECH", false, "2026-06-01", "2026-07-01"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("LC-119-001: Second primary assignment demotes first")
    void lc119001_secondPrimaryDemotesFirst() throws Exception {
        String firstLocation = createLocation("L-014", "Primary One");
        String secondLocation = createLocation("L-015", "Primary Two");
        UUID personId = createPerson();

        String firstAssignment = mockMvc.perform(withAuth(post("/v1/locations/{locationId}/staff", firstLocation)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignStaffPayload(personId, "MECHANIC", true, "2026-01-01", null))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String firstAssignmentId = objectMapper.readTree(firstAssignment).get("assignmentId").asText();

        mockMvc.perform(withAuth(post("/v1/locations/{locationId}/staff", secondLocation)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignStaffPayload(personId, "MECHANIC", true, "2026-06-01", null))))
                .andExpect(status().isCreated());

        String firstLocationStaff = mockMvc.perform(withAuth(get("/v1/locations/{locationId}/staff", firstLocation)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var array = objectMapper.readTree(firstLocationStaff);
        for (var node : array) {
            if (firstAssignmentId.equals(node.get("assignmentId").asText())) {
                org.assertj.core.api.Assertions.assertThat(node.get("isPrimary").asBoolean()).isFalse();
                org.assertj.core.api.Assertions.assertThat(node.get("effectiveTo").asText()).isEqualTo("2026-05-31");
            }
        }
    }

    private String createLocation(String code, String name) throws Exception {
        String response = mockMvc.perform(withAuth(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createLocationPayload(code, name))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("locationId").asText();
    }

    private UUID createPerson() {
                try {
                        String response = mockMvc.perform(withAuth(post("/v1/people")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {
                                                          "firstName": "Test",
                                                          "lastName": "User",
                                                          "primaryEmail": "%s@example.com"
                                                        }
                                                        """.formatted(UUID.randomUUID()))))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString();
                        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
                } catch (Exception e) {
                        throw new RuntimeException(e);
                }
    }

    private String createLocationPayload(String code, String displayName) {
        return """
                {
                  "code": "%s",
                  "displayName": "%s",
                  "locationType": "STORE",
                  "address": "100 Main St",
                  "timezone": "America/New_York"
                }
                """.formatted(code, displayName);
    }

    private String assignStaffPayload(UUID personId, String role, boolean isPrimary, String effectiveFrom, String effectiveTo) {
        String effectiveToLine = effectiveTo == null ? "\"effectiveTo\": null" : "\"effectiveTo\": \"" + effectiveTo + "\"";
        return """
                {
                  "personId": "%s",
                  "role": "%s",
                  "isPrimary": %s,
                  "effectiveFrom": "%s",
                  %s
                }
                """.formatted(personId, role, isPrimary, effectiveFrom, effectiveToLine);
    }
}
