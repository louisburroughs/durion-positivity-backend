package com.positivity.location.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0016 Compliance Contract Tests for CAP-119 / LOCATION-87.
 *
 * These tests enforce that the pos-location module follows ADR-0016 (Location
 * Entity Semantics and Definitions). Tests are written RED-first: they will
 * fail until the implementation is migrated to ADR-0016 values.
 *
 * Key ADR-0016 requirements validated here:
 * - ParentType enum uses: PHYSICAL, ORGANIZATIONAL, FINANCIAL, SHIPPING
 * (NOT the old values: HOME_OFFICE, HEADQUARTERS, REGION, DISTRICT, BILLING)
 * - Location response includes {@code geographicalLocationId} field
 * - Endpoints are served at /v1/locations (not /api/v1/locations)
 * - LocationType is a managed entity (CRUD) not a static enum
 *
 * Contract guide: domains/location/.business-rules/BACKEND_CONTRACT_GUIDE.md
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("CAP-119 / ADR-0016 Location Contract Compliance Tests")
class LocationADR0016ContractBehaviorIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ========== GATEWAY PATH COMPLIANCE ==========

    @Test
    @DisplayName("CP-119-001: POST /v1/locations (not /api/v1/locations) returns 201")
    void CP_119_001_createLocation_at_correct_gateway_path_returns201() throws Exception {
        String payload = """
                {
                    "name": "Main Workshop",
                    "code": "MAIN-WS-001",
                    "type": { "name": "Workshop" },
                    "parents": {}
                }
                """;

        mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "cp-119-001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Main Workshop"))
                .andExpect(jsonPath("$.code").value("MAIN-WS-001"));
    }

    @Test
    @DisplayName("CP-119-002: PUT /v1/locations/{id} updates location")
    void CP_119_002_updateLocation_returns200() throws Exception {
        // Create first
        String createPayload = """
                {
                    "name": "Original Name",
                    "code": "UPDATE-TEST-001",
                    "type": { "name": "Warehouse" },
                    "parents": {}
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "cp-119-002-create"))
                .andExpect(status().isCreated())
                .andReturn();

        String locationId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asString();

        String updatePayload = """
                {
                    "name": "Updated Name",
                    "code": "UPDATE-TEST-001",
                    "type": { "name": "Warehouse" },
                    "parents": {}
                }
                """;

        mockMvc.perform(put("/v1/locations/" + locationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:update")
                .header("X-Correlation-Id", "cp-119-002-update"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"));
    }

    @Test
    @DisplayName("CP-119-003: Duplicate code on POST /v1/locations returns 409")
    void CP_119_003_duplicateCode_returns409() throws Exception {
        String payload = """
                {
                    "name": "Duplicate Test",
                    "code": "DUPE-001",
                    "type": { "name": "Bay" },
                    "parents": {}
                }
                """;

        mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "cp-119-003-first"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "cp-119-003-dupe"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("CP-119-003A: GET /v1/locations/{id}/validation returns exists=true and active=true for existing location")
    void CP_119_003A_locationValidation_existing_returns_exists_and_active() throws Exception {
        String payload = """
                {
                    "name": "Validation Target",
                    "code": "VALIDATE-LOC-001",
                    "type": { "name": "Shop" },
                    "parents": {}
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "cp-119-003a-create"))
                .andExpect(status().isCreated())
                .andReturn();

        String locationId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asString();

        mockMvc.perform(get("/v1/locations/" + locationId + "/validation")
                .header("X-User", "test-user")
                .header("X-Authorities", "location:view")
                .header("X-Correlation-Id", "cp-119-003a-validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(locationId))
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("CP-119-003B: GET /v1/locations/{id}/validation returns exists=false and active=false for missing location")
    void CP_119_003B_locationValidation_missing_returns_false_flags() throws Exception {
        String missingLocationId = "018e1c9f-dead-7000-8000-300000000001";

        mockMvc.perform(get("/v1/locations/" + missingLocationId + "/validation")
                .header("X-User", "test-user")
                .header("X-Authorities", "location:view")
                .header("X-Correlation-Id", "cp-119-003b-validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(missingLocationId))
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.active").value(false));
    }

    // ========== ADR-0016 PARENT TYPE ENUM MIGRATION ==========

    @Test
    @DisplayName("CP-119-004: ADR-0016 ParentType PHYSICAL is accepted when adding parent")
    void CP_119_004_parentType_PHYSICAL_accepted() throws Exception {
        // Create parent location
        String parentPayload = """
                {
                    "name": "Parent Building",
                    "code": "PARENT-PHYS-001",
                    "type": { "name": "Building" },
                    "parents": {}
                }
                """;
        MvcResult parentResult = mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(parentPayload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "cp-119-004-parent"))
                .andExpect(status().isCreated())
                .andReturn();
        String parentId = objectMapper.readTree(parentResult.getResponse().getContentAsString())
                .get("id").asString();

        // Create child location
        String childPayload = """
                {
                    "name": "Child Bay",
                    "code": "CHILD-PHYS-001",
                    "type": { "name": "Bay" },
                    "parents": {}
                }
                """;
        MvcResult childResult = mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(childPayload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "cp-119-004-child"))
                .andExpect(status().isCreated())
                .andReturn();
        String childId = objectMapper.readTree(childResult.getResponse().getContentAsString())
                .get("id").asString();

        // Add PHYSICAL parent relationship — ADR-0016 value
        // RED: this will fail until ParentType enum is migrated from HOME_OFFICE to
        // PHYSICAL
        mockMvc.perform(post("/v1/locations/" + childId + "/parents/" + parentId)
                .param("parentType", "PHYSICAL")
                .header("X-User", "test-user")
                .header("X-Authorities", "location:manage")
                .header("X-Correlation-Id", "cp-119-004-addparent"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CP-119-005: ADR-0016 ParentType ORGANIZATIONAL is accepted")
    void CP_119_005_parentType_ORGANIZATIONAL_accepted() throws Exception {
        // Create locations
        String parentPayload = """
                {
                    "name": "Service Department",
                    "code": "DEPT-ORG-001",
                    "type": { "name": "Department" },
                    "parents": {}
                }
                """;
        MvcResult parentResult = mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(parentPayload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "cp-119-005-parent"))
                .andExpect(status().isCreated())
                .andReturn();
        String parentId = objectMapper.readTree(parentResult.getResponse().getContentAsString())
                .get("id").asString();

        String childPayload = """
                {
                    "name": "Service Bay A",
                    "code": "BAY-ORG-001",
                    "type": { "name": "Bay" },
                    "parents": {}
                }
                """;
        MvcResult childResult = mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(childPayload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "cp-119-005-child"))
                .andExpect(status().isCreated())
                .andReturn();
        String childId = objectMapper.readTree(childResult.getResponse().getContentAsString())
                .get("id").asString();

        // RED: ORGANIZATIONAL is an ADR-0016 value not yet in enum
        mockMvc.perform(post("/v1/locations/" + childId + "/parents/" + parentId)
                .param("parentType", "ORGANIZATIONAL")
                .header("X-User", "test-user")
                .header("X-Authorities", "location:manage")
                .header("X-Correlation-Id", "cp-119-005-addparent"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CP-119-006: Old parentType HOME_OFFICE (pre-ADR-0016) returns 400")
    void CP_119_006_parentType_HOME_OFFICE_is_rejected_after_migration() throws Exception {
        // Create a location
        String parentPayload = """
                {
                    "name": "Headquarters",
                    "code": "HQ-OLD-001",
                    "type": { "name": "Office" },
                    "parents": {}
                }
                """;
        MvcResult parentResult = mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(parentPayload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "cp-119-006-parent"))
                .andExpect(status().isCreated())
                .andReturn();
        String parentId = objectMapper.readTree(parentResult.getResponse().getContentAsString())
                .get("id").asString();

        String childPayload = """
                {
                    "name": "Region Office",
                    "code": "REGION-OLD-001",
                    "type": { "name": "Office" },
                    "parents": {}
                }
                """;
        MvcResult childResult = mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(childPayload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "cp-119-006-child"))
                .andExpect(status().isCreated())
                .andReturn();
        String childId = objectMapper.readTree(childResult.getResponse().getContentAsString())
                .get("id").asString();

        // RED: HOME_OFFICE is the OLD enum value that must be rejected after ADR-0016
        // migration
        // Currently PASSES (wrong) because enum still has HOME_OFFICE; after migration
        // must PASS (correct)
        mockMvc.perform(post("/v1/locations/" + childId + "/parents/" + parentId)
                .param("parentType", "HOME_OFFICE")
                .header("X-User", "test-user")
                .header("X-Authorities", "location:manage")
                .header("X-Correlation-Id", "cp-119-006-addparent"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("CP-119-007: All four ADR-0016 ParentType values are accepted")
    void CP_119_007_all_ADR0016_parentTypes_accepted() throws Exception {
        String[] adr0016ParentTypes = { "PHYSICAL", "ORGANIZATIONAL", "FINANCIAL", "SHIPPING" };

        for (String parentType : adr0016ParentTypes) {
            String code = "ADR-" + parentType + "-001";

            String parentPayload = String.format("""
                    {
                        "name": "Parent for %s",
                        "code": "PAR-%s-001",
                        "type": { "name": "Building" },
                        "parents": {}
                    }
                    """, parentType, parentType);
            MvcResult parentResult = mockMvc.perform(post("/v1/locations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(parentPayload)
                    .header("X-User", "test-user")
                    .header("X-Authorities", "location:create")
                    .header("X-Correlation-Id", "cp-119-007-parent-" + parentType))
                    .andExpect(status().isCreated())
                    .andReturn();
            String parentId = objectMapper.readTree(parentResult.getResponse().getContentAsString())
                    .get("id").asString();

            String childPayload = String.format("""
                    {
                        "name": "Child for %s",
                        "code": "CHD-%s-001",
                        "type": { "name": "Bay" },
                        "parents": {}
                    }
                    """, parentType, parentType);
            MvcResult childResult = mockMvc.perform(post("/v1/locations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(childPayload)
                    .header("X-User", "test-user")
                    .header("X-Authorities", "location:create")
                    .header("X-Correlation-Id", "cp-119-007-child-" + parentType))
                    .andExpect(status().isCreated())
                    .andReturn();
            String childId = objectMapper.readTree(childResult.getResponse().getContentAsString())
                    .get("id").asString();

            // RED: PHYSICAL/ORGANIZATIONAL/FINANCIAL/SHIPPING not in current enum
            mockMvc.perform(post("/v1/locations/" + childId + "/parents/" + parentId)
                    .param("parentType", parentType)
                    .header("X-User", "test-user")
                    .header("X-Authorities", "location:manage")
                    .header("X-Correlation-Id", "cp-119-007-link-" + parentType))
                    .andExpect(status().isOk());
        }
    }

    // ========== GEOGRAPHICAL LOCATION FIELD ==========

    @Test
    @DisplayName("CP-119-010: Location response includes geographicalLocationId when provided")
    void CP_119_010_location_response_includes_geographicalLocationId() throws Exception {
        String geographicalLocationId = "018e1c9f-6b5a-7890-abcd-123456789010";
        String payload = String.format("""
                {
                    "name": "Geo Test Location",
                    "code": "GEO-TEST-001",
                    "type": { "name": "Site" },
                    "geographicalLocationId": "%s",
                    "parents": {}
                }
                """, geographicalLocationId);

        // RED: Location entity currently lacks geographicalLocationId field
        mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "cp-119-010"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.geographicalLocationId").value(geographicalLocationId));
    }

    // ========== VALIDATION TESTS ==========

    @Test
    @DisplayName("VE-119-001: Missing name on POST /v1/locations returns 400")
    void VE_119_001_missing_name_returns400() throws Exception {
        String payload = """
                {
                    "code": "NO-NAME-001",
                    "type": { "name": "Bay" },
                    "parents": {}
                }
                """;

        mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "ve-119-001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VE-119-002: Missing type on POST /v1/locations returns 400")
    void VE_119_002_missing_type_returns400() throws Exception {
        String payload = """
                {
                    "name": "No Type Location",
                    "code": "NO-TYPE-001",
                    "parents": {}
                }
                """;

        mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "ve-119-002"))
                .andExpect(status().isBadRequest());
    }

    // ========== LIST AND DELETE ==========

    @Test
    @DisplayName("CP-119-008: GET /v1/locations returns array")
    void CP_119_008_getLocations_returns_array() throws Exception {
        mockMvc.perform(get("/v1/locations")
                .header("X-User", "test-user")
                .header("X-Authorities", "location:view")
                .header("X-Correlation-Id", "cp-119-008"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("CP-119-009: DELETE /v1/locations/{id} returns 204 for existing location")
    void CP_119_009_deleteLocation_returns204() throws Exception {
        String createPayload = """
                {
                    "name": "To Be Deleted",
                    "code": "DELETE-001",
                    "type": { "name": "Bay" },
                    "parents": {}
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/v1/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:create")
                .header("X-Correlation-Id", "cp-119-009-create"))
                .andExpect(status().isCreated())
                .andReturn();

        String locationId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asString();

        mockMvc.perform(delete("/v1/locations/" + locationId)
                .header("X-User", "test-user")
                .header("X-Authorities", "location:delete")
                .header("X-Correlation-Id", "cp-119-009-delete"))
                .andExpect(status().isNoContent());
    }
}
