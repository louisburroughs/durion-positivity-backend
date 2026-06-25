package com.positivity.people.contract;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.BaseContractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

@DisplayName("User Person Link ContractBehaviorIT")
class UserPersonLinkContractBehaviorIT extends BaseContractIntegrationTest {

    @Test
    @DisplayName("CP-117-020: Create user-person link -> 201")
    void cp117020_createUserPersonLink_returns201() throws Exception {
        UUID personId = createPerson();
        String userId = "user.one";

        mockMvc.perform(withAuth(post("/v1/people/user-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkPayload(userId, personId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(userId))
                .andExpect(jsonPath("$.personId").value(personId.toString()));
    }

    @Test
    @DisplayName("ID-117-020: Duplicate create is idempotent -> 200")
    void id117020_duplicateCreateIdempotent_returns200() throws Exception {
        UUID personId = createPerson();
        String userId = "user.one";

        String payload = linkPayload(userId, personId);

        mockMvc.perform(withAuth(post("/v1/people/user-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(post("/v1/people/user-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(userId));
    }

    @Test
    @DisplayName("VE-117-020: userId linked to different personId -> 409")
    void ve117020_conflictingPersonLink_returns409() throws Exception {
        UUID personA = createPerson();
        UUID personB = createPerson();
        String userId = "user.one";

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
        String userIdOne = "user.one";
        String userIdTwo = "user.two";

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
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].personId", everyItem(is(personId.toString()))))
                .andExpect(jsonPath("$[*].username", containsInAnyOrder(userIdOne, userIdTwo)));
    }

    private UUID createPerson() throws Exception {
        String email = "link.person." + UUID.fromString("00000000-0000-0000-0000-000000000001") + "@example.com";
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

    private String linkPayload(String username, UUID personId) {
        return """
				{
				  "username": "%s",
				  "personId": "%s",
				  "linkType": "PRIMARY",
				  "notes": "created by contract test"
				}
				""".formatted(username, personId);
    }
}
