package com.positivity.location;

import com.positivity.location.config.TestSecurityConfig;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Location Backend Contract Behavioral Tests")
class ContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Test
        @DisplayName("CP-001: Create location returns 201 with current response shape")
        void testCreateLocation_HappyPath() throws Exception {
                String locationCode = uniqueCode("MAIN-STORE");
                mockMvc.perform(withGatewayAuth(post("/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createPayload("Main Store", locationCode, "Workshop"))
                                .header("X-Correlation-Id", "cp-001")))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.id").exists())
                                .andExpect(jsonPath("$.name").value("Main Store"))
                                .andExpect(jsonPath("$.code").value(locationCode))
                                .andExpect(jsonPath("$.active").value(true))
                                .andExpect(jsonPath("$.type.name").value("Workshop"));
        }

        @Test
        @DisplayName("CP-002: Retrieve created location by ID")
        void testGetLocation_HappyPath() throws Exception {
                String locationCode = uniqueCode("SECONDARY");
                MvcResult createResult = mockMvc.perform(withGatewayAuth(post("/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createPayload("Secondary Store", locationCode, "Warehouse"))
                                .header("X-Correlation-Id", "cp-002-create")))
                                .andExpect(status().isCreated())
                                .andReturn();

                String locationId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                                .get("id").asString();

                mockMvc.perform(withGatewayAuth(get("/v1/locations/{id}", locationId)
                                .header("X-Correlation-Id", "cp-002-get")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(locationId))
                                .andExpect(jsonPath("$.name").value("Secondary Store"))
                                .andExpect(jsonPath("$.code").value(locationCode))
                                .andExpect(jsonPath("$.type.name").value("Warehouse"));
        }

        @Test
        @DisplayName("VE-001: Missing required name returns 400")
        void testCreateLocation_MissingName() throws Exception {
                String invalidPayload = """
                                {
                                    "code": "NO-NAME-001",
                                    "type": { "name": "Bay" }
                                }
                                """;

                mockMvc.perform(withGatewayAuth(post("/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidPayload)
                                .header("X-Correlation-Id", "ve-001")))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("VE-002: Duplicate code returns 409")
        void testCreateLocation_DuplicateCode() throws Exception {
                String payload = createPayload("Unique Store", "UNIQUE-CODE-001", "Site");

                mockMvc.perform(withGatewayAuth(post("/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "ve-002-first")))
                                .andExpect(status().isCreated());

                mockMvc.perform(withGatewayAuth(post("/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "ve-002-duplicate")))
                                .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("VE-003: Missing required type returns 400")
        void testCreateLocation_MissingType() throws Exception {
                String invalidPayload = """
                                {
                                    "name": "No Type Store",
                                    "code": "NO-TYPE-001"
                                }
                                """;

                mockMvc.perform(withGatewayAuth(post("/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidPayload)
                                .header("X-Correlation-Id", "ve-003")))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("CP-003: Update location via PUT returns 200")
        void testUpdateLocation_returns200() throws Exception {
                String locationCode = uniqueCode("UPDATE");
                String updatedLocationCode = uniqueCode("UPDATE");
                String locationType = uniqueType("WAREHOUSE");
                String sourceName = uniqueName("Update Source");
                MvcResult createResult = mockMvc.perform(withGatewayAuth(post("/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createPayload(sourceName, locationCode, locationType))
                                .header("X-Correlation-Id", "cp-003-create")))
                                .andExpect(status().isCreated())
                                .andReturn();

                String locationId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                                .get("id").asString();

                String updatePayload = """
                                {
                                    "name": "Updated Name",
                                        "code": "%s",
                                        "type": { "name": "%s" },
                                    "active": false
                                }
                                """.formatted(updatedLocationCode, locationType);

                mockMvc.perform(withGatewayAuth(put("/v1/locations/{id}", locationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(updatePayload)
                                .header("X-Correlation-Id", "cp-003-update")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(locationId))
                                .andExpect(jsonPath("$.name").value("Updated Name"))
                                .andExpect(jsonPath("$.active").value(false));
        }

        @Test
        @DisplayName("CP-004: Get all locations returns array")
        void testGetAllLocations_returnsArray() throws Exception {
                mockMvc.perform(withGatewayAuth(get("/v1/locations")
                                .header("X-Correlation-Id", "cp-004")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$").isArray());
        }

        @Test
        @DisplayName("CP-005: Delete existing location returns 204")
        void testDeleteLocation_returns204() throws Exception {
                String locationCode = uniqueCode("DELETE");
                MvcResult createResult = mockMvc.perform(withGatewayAuth(post("/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createPayload("To Delete", locationCode, "Bay"))
                                .header("X-Correlation-Id", "cp-005-create")))
                                .andExpect(status().isCreated())
                                .andReturn();

                String locationId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                                .get("id").asString();

                mockMvc.perform(withGatewayAuth(delete("/v1/locations/{id}", locationId)
                                .header("X-Correlation-Id", "cp-005-delete")))
                                .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("CP-006: Validation endpoint returns exists=true and active=true for existing location")
        void testLocationValidation_existing_returnsExistsAndActive() throws Exception {
                String locationCode = uniqueCode("VALIDATE");
                String locationType = uniqueType("SHOP");
                String locationName = uniqueName("Validation Target");
                MvcResult createResult = mockMvc.perform(withGatewayAuth(post("/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createPayload(locationName, locationCode, locationType))
                                .header("X-Correlation-Id", "cp-006-create")))
                                .andExpect(status().isCreated())
                                .andReturn();

                String locationId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                                .get("id").asString();

                mockMvc.perform(withGatewayAuth(get("/v1/locations/{id}/validation", locationId)
                                .header("X-Correlation-Id", "cp-006-validate")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.locationId").value(locationId))
                                .andExpect(jsonPath("$.exists").value(true))
                                .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        @DisplayName("CP-007: Add parent relationship with PHYSICAL parentType")
        void testAddParent_PHYSICAL_returns200() throws Exception {
                String parentCode = uniqueCode("PARENT");
                String childCode = uniqueCode("CHILD");
                String parentType = uniqueType("BUILDING");
                String childType = uniqueType("BAY");
                String parentName = uniqueName("Parent Building");
                String childName = uniqueName("Child Bay");
                MvcResult parentResult = mockMvc.perform(withGatewayAuth(post("/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createPayload(parentName, parentCode, parentType))
                                .header("X-Correlation-Id", "cp-007-parent")))
                                .andExpect(status().isCreated())
                                .andReturn();

                String parentId = objectMapper.readTree(parentResult.getResponse().getContentAsString())
                                .get("id").asString();

                MvcResult childResult = mockMvc.perform(withGatewayAuth(post("/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createPayload(childName, childCode, childType))
                                .header("X-Correlation-Id", "cp-007-child")))
                                .andExpect(status().isCreated())
                                .andReturn();

                String childId = objectMapper.readTree(childResult.getResponse().getContentAsString())
                                .get("id").asString();

                mockMvc.perform(withGatewayAuth(post("/v1/locations/{childId}/parents/{parentId}", childId, parentId)
                                .param("parentType", "PHYSICAL")
                                .header("X-Correlation-Id", "cp-007-link")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.childId").value(childId))
                                .andExpect(jsonPath("$.parentId").value(parentId))
                                .andExpect(jsonPath("$.parentType").value("PHYSICAL"));
        }

        private String createPayload(String name, String code, String typeName) {
                return String.format(
                                """
                                                {
                                                    "name": "%s",
                                                    "code": "%s",
                                                    "type": { "name": "%s" }
                                                }
                                                """,
                                name, code, typeName);
        }

        private String uniqueCode(String prefix) {
                return prefix + "-" + UUID.fromString("00000000-0000-0000-0000-000000000001").toString().substring(0, 8)
                                .toUpperCase();
        }

        private String uniqueType(String prefix) {
                return prefix + "-" + UUID.fromString("00000000-0000-0000-0000-000000000001").toString().substring(0, 8)
                                .toUpperCase();
        }

        private String uniqueName(String prefix) {
                return prefix + " " + UUID.fromString("00000000-0000-0000-0000-000000000001").toString().substring(0, 8)
                                .toUpperCase();
        }
}
