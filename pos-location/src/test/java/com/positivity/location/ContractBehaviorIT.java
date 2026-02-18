package com.positivity.location;

import com.positivity.location.internal.entity.LocationType;
import com.positivity.location.internal.repository.LocationTypeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "spring.datasource.url=jdbc:h2:mem:location-contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.flyway.enabled=false",
                "eureka.client.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("CAP-119 Location CRUD ContractBehaviorIT")
class ContractBehaviorIT {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private LocationTypeRepository locationTypeRepository;

        @Test
        @DisplayName("CP-119-001: List locations returns 200")
        void cp119001_listLocations() throws Exception {
                UUID typeId = ensureLocationType("LIST");
                createLocation("List Location", typeId);

                mockMvc.perform(get("/v1/locations"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(1)));
        }

        @Test
        @DisplayName("CP-119-002: Create location returns 201")
        void cp119002_createLocation() throws Exception {
                UUID typeId = ensureLocationType("CREATE");

                mockMvc.perform(post("/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createPayload("Main Shop", typeId)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.name").value("Main Shop"));
        }

        @Test
        @DisplayName("CP-119-003: Get by id returns 200")
        void cp119003_getById() throws Exception {
                UUID typeId = ensureLocationType("GET");
                UUID locationId = createLocation("Fetch Shop", typeId);

                mockMvc.perform(get("/v1/locations/{locationId}", locationId))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(locationId.toString()))
                                .andExpect(jsonPath("$.name").value("Fetch Shop"));
        }

        @Test
        @DisplayName("VE-119-001: Get missing location returns 404")
        void ve119001_getMissing() throws Exception {
                mockMvc.perform(get("/v1/locations/{locationId}", UUID.randomUUID()))
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("VE-119-002: Duplicate code returns 409")
        void ve119002_duplicateCode() throws Exception {
                UUID missingTypeId = UUID.randomUUID();

                mockMvc.perform(post("/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createPayload("Second", missingTypeId)))
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("CP-119-004: Delete location returns 204")
        void cp119004_deleteLocation() throws Exception {
                UUID typeId = ensureLocationType("DELETE");
                UUID locationId = createLocation("Delete Me", typeId);

                mockMvc.perform(delete("/v1/locations/{locationId}", locationId))
                                .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("CP-119-005: Update location returns 200")
        void cp119005_updateLocation() throws Exception {
                UUID typeId = ensureLocationType("UPDATE");
                UUID locationId = createLocation("Original Name", typeId);

                mockMvc.perform(put("/v1/locations/{locationId}", locationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(updatePayload("Updated Name", typeId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.name").value("Updated Name"));
        }

        private UUID ensureLocationType(String suffix) {
                LocationType locationType = new LocationType();
                locationType.setName("TYPE_" + suffix + "_" + UUID.randomUUID());
                locationType.setDescription("Type for " + suffix);
                return locationTypeRepository.save(locationType).getId();
        }

        private UUID createLocation(String name, UUID typeId) throws Exception {
                String response = mockMvc.perform(post("/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createPayload(name, typeId)))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> body = objectMapper.readValue(response, java.util.Map.class);
                return UUID.fromString((String) body.get("id"));
        }

        private String createPayload(String name, UUID typeId) {
                return """
                                {
                                  "name": "%s",
                                  "typeId": "%s"
                                }
                                                                """.formatted(name, typeId);
        }

        private String updatePayload(String name, UUID typeId) {
                return """
                                {
                                  "name": "%s",
                                                                        "typeId": "%s"
                                }
                                                                """.formatted(name, typeId);
        }
}
