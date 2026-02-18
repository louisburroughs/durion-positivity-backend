package com.positivity.people.contract;

import com.positivity.people.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("User Person Link ContractBehaviorIT")
class UserPersonLinkContractBehaviorIT extends BaseIntegrationTest {

    @Test
    @DisplayName("CP-117-020: Create user-person link -> 201")
    void cp117020_createUserPersonLink_returns201() throws Exception {
        UUID personId = createPerson();
        UUID userId = UUID.randomUUID();

        mockMvc.perform(withAuth(post("/v1/people/user-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(linkPayload(userId, personId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.personId").value(personId.toString()));
    }

    @Test
    @DisplayName("ID-117-020: Duplicate create is idempotent -> 200")
    void id117020_duplicateCreateIdempotent_returns200() throws Exception {
        UUID personId = createPerson();
        UUID userId = UUID.randomUUID();

        String payload = linkPayload(userId, personId);

        mockMvc.perform(withAuth(post("/v1/people/user-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(post("/v1/people/user-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()));
    }

    @Test
    @DisplayName("VE-117-020: userId linked to different personId -> 409")
    void ve117020_conflictingPersonLink_returns409() throws Exception {
        UUID personA = createPerson();
        UUID personB = createPerson();
        UUID userId = UUID.randomUUID();

        mockMvc.perform(withAuth(post("/v1/people/user-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(linkPayload(userId, personA))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(post("/v1/people/user-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(linkPayload(userId, personB))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("CP-117-021: Get links by person -> 200")
    void cp117021_getLinksByPerson_returns200() throws Exception {
        UUID personId = createPerson();
        UUID userIdOne = UUID.randomUUID();
        UUID userIdTwo = UUID.randomUUID();

        mockMvc.perform(withAuth(post("/v1/people/user-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(linkPayload(userIdOne, personId))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(post("/v1/people/user-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(linkPayload(userIdTwo, personId))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(get("/v1/people/user-links/{personId}", personId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].personId").value(personId.toString()));
    }

    private UUID createPerson() throws Exception {
        String email = "link.person." + UUID.randomUUID() + "@example.com";
        String payload = """
                {
                  "firstName": "Link",
                  "lastName": "Person",
                                                                        "primaryEmail": "%s",
                  "secondaryEmail": "link.person.secondary@example.com",
                  "phoneNumbers": ["555-117-0200"]
                }
                                                                """.formatted(email);

        String response = mockMvc.perform(withAuth(post("/v1/people")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private String linkPayload(UUID userId, UUID personId) {
        return """
                {
                  "userId": "%s",
                  "personId": "%s",
                  "linkType": "PRIMARY",
                  "notes": "created by contract test"
                }
                """.formatted(userId, personId);
    }
}
