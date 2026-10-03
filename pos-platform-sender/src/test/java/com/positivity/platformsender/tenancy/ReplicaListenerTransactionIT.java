package com.positivity.platformsender.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.platformsender.internal.repository.ExtPeopleContactPersonRepository;
import com.positivity.platformsender.internal.repository.ProcessedEventRepository;
import com.positivity.platformsender.internal.service.PeopleContactEventsListener;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the replica listeners' transaction shape (ADR-0044 amendment 2026-09-23) against a real
 * transaction manager and Postgres, which the mock-based listener tests cannot do: a permanent
 * database failure inside the handler (a contact point longer than the replica column) rolls back
 * only the handler's own work, the {@code processed_events} mark still commits, and the failure does
 * not poison an enclosing transaction. {@code CustomerEventsListener} has the identical shape.
 */
@DisplayName("Replica listener transaction shape (Postgres)")
class ReplicaListenerTransactionIT extends PostgresTenancyTestBase {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ExtPeopleContactPersonRepository personRepository;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    private PeopleContactEventsListener listener;
    private String eventId;
    private UUID personId;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        eventId = UUID.randomUUID().toString();
        personId = UUID.randomUUID();
        listener = new PeopleContactEventsListener(
                Clock.systemUTC(),
                objectMapper,
                processedEventRepository,
                personRepository,
                meterRegistry,
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a permanent database failure is recorded as processed and does not throw")
    void permanentFailureIsRecorded() {
        assertThatCode(() -> listener.onPeopleContactEvent(oversizedPerson())).doesNotThrowAnyException();

        assertFailedApplyRolledBackAndEventWasRecorded();
    }

    @Test
    @DisplayName("inside an enclosing transaction, the failure does not poison its commit")
    void failureDoesNotPoisonEnclosingTransaction() {
        TransactionTemplate enclosing = new TransactionTemplate(transactionManager);

        assertThatCode(() -> enclosing.executeWithoutResult(_ -> listener.onPeopleContactEvent(oversizedPerson())))
                .doesNotThrowAnyException();

        assertFailedApplyRolledBackAndEventWasRecorded();
    }

    @Test
    @DisplayName("a good event applies and marks in one commit")
    void goodEventApplies() {
        listener.onPeopleContactEvent(person("ada@example.com"));

        assertThat(personRepository.findById(personId).orElseThrow().getEmail()).isEqualTo("ada@example.com");
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
    }

    private void assertFailedApplyRolledBackAndEventWasRecorded() {
        assertThat(processedEventRepository.existsById(eventId))
                .as("the processed mark must survive the apply's rollback")
                .isTrue();
        assertThat(personRepository.existsById(personId))
                .as("the failed apply's own replica write must have rolled back")
                .isFalse();
    }

    /** An email longer than {@code ext_people_contact_person.email}'s 255 characters. */
    private String oversizedPerson() {
        return person("a".repeat(300) + "@example.com");
    }

    private String person(String email) {
        return """
                {"eventId":"%s","eventType":"people-contact.person.updated","aggregateVersion":1,
                 "payload":{"personId":"%s","contactPoints":[{"contactType":"EMAIL","value":"%s","primary":true}]}}
                """.formatted(eventId, personId, email);
    }
}
