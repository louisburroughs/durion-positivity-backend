package com.positivity.peoplecontact.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.peoplecontact.internal.entity.Person;
import com.positivity.peoplecontact.internal.entity.PersonContactPoint;
import com.positivity.peoplecontact.internal.enums.ContactPointType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Executes {@link PersonSpecifications#directoryFilter} against a real JPA metamodel so
 * the query's attribute references are resolved (the mock-based unit test cannot catch a
 * predicate that references a non-existent attribute). Guards the people directory/search
 * paths against regressions like a filter referencing a dropped Person column.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:specquery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.flyway.enabled=false",
            "eureka.client.enabled=false"
        })
@ActiveProfiles("test")
class PersonSpecificationsQueryIT {

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private PersonContactPointRepository contactPointRepository;

    @MockitoBean
    private com.positivity.peoplecontact.internal.client.SecurityServiceClient securityServiceClient;

    private UUID alice;
    private UUID bob;

    @BeforeEach
    void seed() {
        contactPointRepository.deleteAll();
        personRepository.deleteAll();

        Person a = personRepository.saveAndFlush(
                Person.builder().firstName("Alice").lastName("Anderson").build());
        Person b = personRepository.saveAndFlush(
                Person.builder().firstName("Bob").lastName("Brown").build());
        alice = a.getId();
        bob = b.getId();

        contactPointRepository.saveAndFlush(PersonContactPoint.builder()
                .personId(bob)
                .contactType(ContactPointType.EMAIL)
                .value("bob@example.com")
                .isPrimary(true)
                .build());
    }

    @Test
    void search_matchesByName() {
        List<Person> result = personRepository.findAll(PersonSpecifications.directoryFilter("ali"));
        assertThat(result).extracting(Person::getId).containsExactly(alice);
    }

    @Test
    void search_matchesByEmailContactPoint() {
        List<Person> result = personRepository.findAll(PersonSpecifications.directoryFilter("bob@example"));
        assertThat(result).extracting(Person::getId).containsExactly(bob);
    }

    @Test
    void search_blankReturnsAll() {
        List<Person> result = personRepository.findAll(PersonSpecifications.directoryFilter("  "));
        assertThat(result).extracting(Person::getId).containsExactlyInAnyOrder(alice, bob);
    }
}
