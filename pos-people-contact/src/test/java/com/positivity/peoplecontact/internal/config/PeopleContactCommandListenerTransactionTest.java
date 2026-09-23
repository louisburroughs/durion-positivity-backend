package com.positivity.peoplecontact.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.peoplecontact.internal.dto.LinkUserToPersonRequest;
import com.positivity.peoplecontact.internal.dto.PersonResponse;
import com.positivity.peoplecontact.internal.dto.UserPersonLinkResponse;
import com.positivity.peoplecontact.internal.exception.UserAlreadyLinkedException;
import com.positivity.peoplecontact.internal.repository.ProcessedEventRepository;
import com.positivity.peoplecontact.internal.service.LinkCommandHandler;
import com.positivity.peoplecontact.internal.service.OutboxReplayService;
import com.positivity.peoplecontact.internal.service.PersonUpsertCommandHandler;
import com.positivity.peoplecontact.internal.service.UserPersonLinkService;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the link-command transaction shape (#2146) against a real transaction manager, which the
 * mock-based {@link PeopleContactCommandListenerTest} cannot do.
 *
 * <p>Link commands are applied by {@link LinkCommandHandler} through {@link UserPersonLinkService},
 * a {@code @Transactional} service. A conflict leaving it marks the transaction it joined
 * rollback-only, so when the handler ran each command inside its own {@code @Transactional}, the
 * conflict it caught and logged still made the commit after the catch throw
 * {@code UnexpectedRollbackException}, and the {@code processed_events} mark the conflict was
 * meant to record was rolled back with it.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:people-contact-listener-tx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.flyway.enabled=false",
            "eureka.client.enabled=false",
            "pos.security.permission-registration.enabled=false"
        })
@ActiveProfiles("test")
@DisplayName("PeopleContactCommandListener link-command transaction shape")
class PeopleContactCommandListenerTransactionTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private OutboxReplayService outboxReplayService;

    @Autowired
    private PersonUpsertCommandHandler personUpsertCommandHandler;

    @Autowired
    private FailingUserPersonLinkService failingLinkService;

    private PeopleContactCommandListener listener;
    private String commandId;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        commandId = UUID.randomUUID().toString();
        failingLinkService.reset();
        Clock clock = Clock.systemUTC();
        listener = new PeopleContactCommandListener(
                clock,
                new ObjectMapper(),
                outboxReplayService,
                personUpsertCommandHandler,
                new LinkCommandHandler(clock, failingLinkService, processedEventRepository, transactionManager),
                processedEventRepository);
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteById(commandId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("A permanent conflict inside a transactional service still records the command and does not throw")
    void permanentConflictInsideTransactionalServiceIsRecorded() {
        assertThatCode(() -> listener.onCommand(linkCreateCommand())).doesNotThrowAnyException();

        assertServiceFailedInsideTransactionAndCommandWasRecorded();
    }

    /**
     * The shape the defect had: the whole command inside one enclosing transaction, as the
     * {@code @Transactional} handler methods ran it. The conflict must not mark that transaction
     * rollback-only, or its commit throws {@code UnexpectedRollbackException} and the mark is lost.
     */
    @Test
    @DisplayName("Inside an enclosing transaction, the service's conflict does not poison its commit")
    void permanentConflictDoesNotPoisonAnEnclosingTransaction() {
        TransactionTemplate enclosing = new TransactionTemplate(transactionManager);

        assertThatCode(() -> enclosing.executeWithoutResult(_ -> listener.onCommand(linkCreateCommand())))
                .doesNotThrowAnyException();

        assertServiceFailedInsideTransactionAndCommandWasRecorded();
    }

    private void assertServiceFailedInsideTransactionAndCommandWasRecorded() {
        assertThat(failingLinkService.sawActiveTransaction())
                .as("the service must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(processedEventRepository.existsById(commandId))
                .as("the processed mark must survive the service's rollback")
                .isTrue();
    }

    private String linkCreateCommand() {
        return """
                {"commandType":"people-contact.user-person-link.create-requested","eventId":"%s",
                 "payload":{"personId":"%s","username":"jdoe"}}
                """.formatted(commandId, UUID.randomUUID());
    }

    @TestConfiguration
    static class FailingLinkServiceConfiguration {

        @Bean
        @Primary
        FailingUserPersonLinkService failingUserPersonLinkService() {
            return new FailingUserPersonLinkService();
        }
    }

    /** Mirrors the real service's shape: a {@code @Transactional} link that conflicts. */
    @Transactional
    static class FailingUserPersonLinkService implements UserPersonLinkService {

        private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);

        public boolean sawActiveTransaction() {
            return sawActiveTransaction.get();
        }

        public void reset() {
            sawActiveTransaction.set(false);
        }

        @Override
        public @NonNull UserPersonLinkResponse linkUserToPerson(@NonNull LinkUserToPersonRequest request) {
            sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw new UserAlreadyLinkedException(request.getUsername());
        }

        @Override
        public boolean linkExistsByUsername(@NonNull String username) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean linkExistsByUsernameAndPersonId(@NonNull String username, @NonNull UUID personId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull UserPersonLinkResponse createUserLink(@NonNull String username, @NonNull UUID personId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull List<UserPersonLinkResponse> getUserLinks(@NonNull UUID personId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unlinkUserFromPerson(@NonNull String username) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull PersonResponse findPersonByUsername(@NonNull String username) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull List<String> findUsernamesByPersonId(@NonNull UUID personId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull UserPersonLinkResponse findLinkByPersonId(@NonNull UUID personId) {
            throw new UnsupportedOperationException();
        }
    }
}
