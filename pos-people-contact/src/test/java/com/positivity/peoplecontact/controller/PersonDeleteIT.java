package com.positivity.peoplecontact.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.peoplecontact.internal.entity.Person;
import com.positivity.peoplecontact.internal.entity.PersonContactPoint;
import com.positivity.peoplecontact.internal.entity.UserPersonLink;
import com.positivity.peoplecontact.internal.enums.ContactPointType;
import com.positivity.peoplecontact.internal.enums.UserLinkStatus;
import com.positivity.peoplecontact.internal.repository.PersonContactPointRepository;
import com.positivity.peoplecontact.internal.repository.PersonRepository;
import com.positivity.peoplecontact.internal.repository.UserPersonLinkRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

/**
 * Integration coverage for the person hard-delete flow (#881): contact points are removed in
 * the same transaction, and a person that still has {@code user_person_links} rows answers 409
 * CONFLICT with a {@code nextAction} instead of surfacing the FK violation as a 500.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:persondelete;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
class PersonDeleteIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private PersonContactPointRepository personContactPointRepository;

    @Autowired
    private UserPersonLinkRepository userPersonLinkRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.positivity.peoplecontact.internal.client.SecurityServiceClient securityServiceClient;

    @BeforeEach
    void resetData() {
        userPersonLinkRepository.deleteAll();
        personContactPointRepository.deleteAll();
        personRepository.deleteAll();
    }

    private HttpHeaders authenticatedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-User", "test-user");
        headers.add("X-Authorities", "people-contact:person:delete");
        return headers;
    }

    private Person createPerson() {
        return personRepository.save(
                Person.builder().firstName("Del").lastName("Etable").build());
    }

    private void addContactPoint(UUID personId, ContactPointType type, String value) {
        personContactPointRepository.save(PersonContactPoint.builder()
                .personId(personId)
                .contactType(type)
                .value(value)
                .isPrimary(true)
                .build());
    }

    @Test
    @DisplayName("Deleting a person removes its contact points in the same transaction")
    void deleteRemovesContactPoints() {
        Person person = createPerson();
        addContactPoint(person.getId(), ContactPointType.EMAIL, "del@example.com");
        addContactPoint(person.getId(), ContactPointType.PHONE_MOBILE, "+15550100");

        ResponseEntity<Void> response = restTemplate.exchange(
                "/v1/people/{id}",
                HttpMethod.DELETE,
                new HttpEntity<>(authenticatedHeaders()),
                Void.class,
                person.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(personRepository.findById(person.getId())).isEmpty();
        assertThat(personContactPointRepository.findByPersonId(person.getId())).isEmpty();
    }

    @Test
    @DisplayName("Deleting a person with linked users answers 409 with a nextAction, not a 500")
    void deleteWithLinkedUsersConflicts() {
        Person person = createPerson();
        addContactPoint(person.getId(), ContactPointType.EMAIL, "linked@example.com");
        UserPersonLink link = new UserPersonLink();
        link.setUsername("linked-user");
        link.setPerson(person);
        link.setStatus(UserLinkStatus.ACTIVE);
        userPersonLinkRepository.save(link);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/v1/people/{id}",
                HttpMethod.DELETE,
                new HttpEntity<>(authenticatedHeaders()),
                new ParameterizedTypeReference<>() {},
                person.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat((String) response.getBody().get("detail")).contains("linked user");
        assertThat((String) response.getBody().get("nextAction")).contains("Unlink");
        // Nothing was deleted: the person, its link, and its contact points survive.
        assertThat(personRepository.findById(person.getId())).isPresent();
        assertThat(userPersonLinkRepository.findByPerson_Id(person.getId())).hasSize(1);
        assertThat(personContactPointRepository.findByPersonId(person.getId())).hasSize(1);
    }

    @Test
    @DisplayName("Deleting an unknown person answers 404")
    void deleteUnknownPersonIs404() {
        ResponseEntity<Void> response = restTemplate.exchange(
                "/v1/people/{id}",
                HttpMethod.DELETE,
                new HttpEntity<>(authenticatedHeaders()),
                Void.class,
                UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
