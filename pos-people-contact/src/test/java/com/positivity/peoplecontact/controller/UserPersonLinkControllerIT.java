package com.positivity.peoplecontact.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.peoplecontact.internal.repository.PersonRepository;
import com.positivity.peoplecontact.internal.repository.UserPersonLinkRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:userpersonlink;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.flyway.enabled=false",
            "eureka.client.enabled=false"
        })
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class UserPersonLinkControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private UserPersonLinkRepository userPersonLinkRepository;

    // The link is stored directly with the username, so no cross-service username
    // resolution is needed. The mocked client is harmless and left in place.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.positivity.peoplecontact.internal.client.SecurityServiceClient securityServiceClient;

    @BeforeEach
    void resetData() {
        userPersonLinkRepository.deleteAll();
        personRepository.deleteAll();
    }

    private HttpHeaders authenticatedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-User", "test-user");
        headers.add(
                "X-Authorities",
                "people-contact:person:create,people-contact:userLink:write,people-contact:userLink:view");
        return headers;
    }

    @Test
    void linkUserToPerson_success_returns201() {
        UUID personId = createPerson("Casey", "Lane", "casey@example.com");
        String username = "caseylane";

        HttpEntity<String> entity = new HttpEntity<>(
                createLinkRequestJson(username, personId, "PRIMARY", "integration test"), authenticatedHeaders());
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/v1/people/users/link", HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry("username", username);
        assertThat(response.getBody()).containsEntry("personId", personId.toString());
    }

    @Test
    void linkUserToPerson_personNotFound_returns404() {
        String username = "caseylane";
        HttpEntity<String> entity = new HttpEntity<>(
                createLinkRequestJson(
                        username, UUID.fromString("00000000-0000-0000-0000-000000000001"), "PRIMARY", null),
                authenticatedHeaders());
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/v1/people/users/link", HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("detail").toString()).contains("Person not found");
    }

    @Test
    void linkUserToPerson_alreadyLinked_returns200() {
        UUID personId = createPerson("Taylor", "Ray", "taylor@example.com");
        String username = "taylorray";
        HttpEntity<String> entity =
                new HttpEntity<>(createLinkRequestJson(username, personId, "PRIMARY", null), authenticatedHeaders());

        ResponseEntity<Map<String, Object>> firstResponse = restTemplate.exchange(
                "/v1/people/users/link", HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> secondResponse = restTemplate.exchange(
                "/v1/people/users/link", HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondResponse.getBody()).isNotNull();
        assertThat(secondResponse.getBody()).containsEntry("username", username);
    }

    @Test
    void linkUserToPerson_invalidRequest_returns400() {
        String invalidPayload = "{\"username\":\"\",\"personId\":\"not-a-uuid\"}";

        HttpEntity<String> entity = new HttpEntity<>(invalidPayload, authenticatedHeaders());
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/v1/people/users/link", HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry("status", 400);
        assertThat(response.getBody()).containsEntry("error", "Bad Request");
        assertThat(response.getBody()).containsEntry("path", "/v1/people/users/link");
    }

    @Test
    void unlinkUserFromPerson_success_returns204() {
        UUID personId = createPerson("Chris", "Doe", "chris@example.com");
        String username = "chrisdoe";
        linkUser(username, personId);

        HttpEntity<Void> entity = new HttpEntity<>(authenticatedHeaders());
        ResponseEntity<Void> response = restTemplate.exchange(
                "/v1/people/users/{username}/link", HttpMethod.DELETE, entity, Void.class, username);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void unlinkUserFromPerson_notFound_returns404() {
        HttpEntity<Void> entity = new HttpEntity<>(authenticatedHeaders());
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/v1/people/users/{username}/link",
                HttpMethod.DELETE,
                entity,
                new ParameterizedTypeReference<>() {},
                "unknownuser");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("detail").toString()).contains("No person link found");
    }

    @Test
    void findPersonByUserId_success_returns200() {
        UUID personId = createPerson("Jordan", "Case", "jordan@example.com");
        String username = "jordancase";
        linkUser(username, personId);

        HttpEntity<Void> entity = new HttpEntity<>(authenticatedHeaders());
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/v1/people/users/{username}/person",
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {},
                username);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry("id", personId.toString());
        assertThat(response.getBody()).containsEntry("firstName", "Jordan");
    }

    @Test
    void findPersonByUserId_notFound_returns404() {
        HttpEntity<Void> entity = new HttpEntity<>(authenticatedHeaders());
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/v1/people/users/{username}/person",
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {},
                "unknownuser");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("detail").toString()).contains("No person link found");
    }

    @Test
    void findUserIdsByPersonId_success_returns200() {
        UUID personId = createPerson("Dana", "Stone", "dana@example.com");
        String userA = "danastone";
        String userB = "danastone2";
        linkUser(userA, personId);
        linkUser(userB, personId);

        HttpEntity<Void> entity = new HttpEntity<>(authenticatedHeaders());
        ResponseEntity<List<String>> response = restTemplate.exchange(
                "/v1/people/{personId}/users", HttpMethod.GET, entity, new ParameterizedTypeReference<>() {}, personId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactlyInAnyOrder(userA, userB);
    }

    @Test
    void findUserIdsByPersonId_empty_returns200() {
        UUID personId = createPerson("Robin", "Mills", "robin@example.com");

        HttpEntity<Void> entity = new HttpEntity<>(authenticatedHeaders());
        ResponseEntity<List<String>> response = restTemplate.exchange(
                "/v1/people/{personId}/users", HttpMethod.GET, entity, new ParameterizedTypeReference<>() {}, personId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    private UUID createPerson(String firstName, String lastName, String email) {
        String payload = "{" + "\"firstName\":\"" + firstName + "\"," + "\"lastName\":\"" + lastName + "\","
                + "\"primaryEmail\":\"" + email + "\"," + "\"phoneNumbers\":[\"555-1212\"]" + "}";
        HttpEntity<String> entity = new HttpEntity<>(payload, authenticatedHeaders());
        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange("/v1/people", HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return UUID.fromString(response.getBody().get("id").toString());
    }

    private void linkUser(String username, UUID personId) {
        HttpEntity<String> entity =
                new HttpEntity<>(createLinkRequestJson(username, personId, "PRIMARY", null), authenticatedHeaders());
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/v1/people/users/link", HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private String createLinkRequestJson(String username, UUID personId, String linkType, String notes) {
        String notesPart = notes == null ? "null" : "\"" + notes + "\"";
        return "{" + "\"username\":\"" + username + "\"," + "\"personId\":\"" + personId + "\"," + "\"linkType\":\""
                + linkType + "\"," + "\"notes\":" + notesPart + "}";
    }
}
