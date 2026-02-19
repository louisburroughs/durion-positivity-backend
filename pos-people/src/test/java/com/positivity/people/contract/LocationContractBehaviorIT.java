package com.positivity.people.contract;

import com.positivity.people.BaseIntegrationTest;
import com.positivity.people.internal.client.LocationReferenceClient;
import com.positivity.people.internal.exception.LocationNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("CAP-119 Location & Staffing ContractBehaviorIT")
class LocationContractBehaviorIT extends BaseIntegrationTest {

    @MockitoBean
    private LocationReferenceClient locationReferenceClient;

    @Test
    @DisplayName("CP-119-010: Assign staff to location")
    void cp119010_assignStaff() throws Exception {
        UUID locationId = UUID.randomUUID();
        UUID personId = createPerson();
        doNothing().when(locationReferenceClient).assertLocationExists(locationId);

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
        UUID locationId = UUID.randomUUID();
        UUID personId = createPerson();
        doNothing().when(locationReferenceClient).assertLocationExists(locationId);

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
        UUID locationId = UUID.randomUUID();
        UUID personId = createPerson();
        doNothing().when(locationReferenceClient).assertLocationExists(locationId);

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
        UUID locationId = UUID.randomUUID();
        UUID personId = createPerson();
        doNothing().when(locationReferenceClient).assertLocationExists(locationId);

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
        UUID firstLocation = UUID.randomUUID();
        UUID secondLocation = UUID.randomUUID();
        UUID personId = createPerson();
        doNothing().when(locationReferenceClient).assertLocationExists(any(UUID.class));

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
        boolean firstAssignmentFound = false;
        for (var node : array) {
            if (firstAssignmentId.equals(node.get("assignmentId").asText())) {
                firstAssignmentFound = true;
                org.assertj.core.api.Assertions.assertThat(node.get("isPrimary").asBoolean()).isFalse();
                org.assertj.core.api.Assertions.assertThat(node.get("effectiveTo").asText()).isEqualTo("2026-05-31");
            }
        }
        org.assertj.core.api.Assertions.assertThat(firstAssignmentFound).isTrue();
    }

    @Test
    @DisplayName("VE-119-011: Assign to non-existent location -> 404")
    void ve119011_assignToMissingLocation() throws Exception {
        UUID locationId = UUID.randomUUID();
        UUID personId = createPerson();
        doThrow(new LocationNotFoundException(locationId))
                .when(locationReferenceClient)
                .assertLocationExists(locationId);

        mockMvc.perform(withAuth(post("/v1/locations/{locationId}/staff", locationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignStaffPayload(personId, "TECH", false, "2026-01-01", null))))
                .andExpect(status().isNotFound());
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

    private String assignStaffPayload(UUID personId, String role, boolean isPrimary, String effectiveFrom,
            String effectiveTo) {
        String effectiveToLine = effectiveTo == null ? "\"effectiveTo\": null"
                : "\"effectiveTo\": \"" + effectiveTo + "\"";
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
