package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shopmanager.internal.dto.WorkorderStatusChangedEvent;
import com.positivity.shopmanager.internal.entity.ExtWorkorderReplica;
import com.positivity.shopmanager.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The appointment sync cannot poison the workorder replica write (#1658 review).
 *
 * <p>{@code WorkorderEventsListener} writes the {@code ext_workorder} row and the
 * {@code processed_events} idempotency row in one transaction and raises an in-process
 * {@link WorkorderStatusChangedEvent} for the appointment sync. Handling that event inline made a
 * failing sync catastrophic rather than merely degraded: the consumer's catch-all swallowed the
 * exception, but the sync's own {@code @Transactional} boundary had already marked the shared
 * transaction rollback-only, so the dedup insert failed <em>at commit</em>. The replica update was
 * lost and Kafka redelivered the same record forever — a poison-message loop.
 *
 * <p>The stub below reproduces exactly that adversarial downstream: a {@code @Transactional} bean,
 * default {@code REQUIRED} propagation, that throws. The listener must survive it whether the sync
 * joins the caller's transaction or opens its own, so the harsher of the two is the one tested.
 *
 * <p>This needs a real commit, so the class is deliberately not {@code @Transactional} and cleans
 * up after itself: a rolled-back test cannot tell the fixed shape from the broken one, because the
 * broken one only fails at commit time.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(WorkorderStatusSyncIsolationTest.ThrowingAppointmentSyncConfig.class)
@DisplayName("WorkorderEventsListener — the appointment sync cannot poison the replica write")
class WorkorderStatusSyncIsolationTest {

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000009a1");
    private static final String EVENT_ID = "01960003-0000-7000-8000-0000000009a1";

    @Autowired
    private ThrowingAppointmentSync throwingAppointmentSync;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ExtWorkorderReplicaRepository extWorkorderReplicaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        extWorkorderReplicaRepository.deleteById(WORKORDER_ID);
        processedEventRepository.deleteById(EVENT_ID);
    }

    @Test
    @DisplayName("#1658 - a throwing appointment sync leaves the replica updated and the event processed")
    void failingAppointmentSyncDoesNotRollBackTheReplicaOrTheDedupRow() {
        assertThatCode(this::consumeInOneTransaction).doesNotThrowAnyException();

        // The sync really did run and really did fail — otherwise this test proves nothing.
        assertThat(throwingAppointmentSync.invocations())
                .as("the appointment sync must have been reached and thrown")
                .isEqualTo(1);

        ExtWorkorderReplica replica =
                extWorkorderReplicaRepository.findById(WORKORDER_ID).orElse(null);
        assertThat(replica)
                .as("the replica write must survive a failing downstream sync")
                .isNotNull();
        assertThat(replica.getStatus()).isEqualTo("WORK_IN_PROGRESS");
        assertThat(processedEventRepository.existsById(EVENT_ID))
                .as("the idempotency row must be committed, or the record is redelivered forever")
                .isTrue();
    }

    @SuppressWarnings("unchecked")
    private void consumeInOneTransaction() {
        WorkorderEventsListener listener = new WorkorderEventsListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEventRepository,
                extWorkorderReplicaRepository,
                applicationEventPublisher,
                Mockito.mock(ObjectProvider.class));
        // A hand-built instance gets no @Transactional proxy, so the transaction the production
        // listener runs inside is supplied here explicitly. Its commit is where the broken shape
        // used to blow up with UnexpectedRollbackException.
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> listener.onWorkorderEvent(envelope()));
    }

    private String envelope() {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":1,"payload":{
                  "workorderId":"%s","workorderNumber":"WO-SYNC-1","status":"WORK_IN_PROGRESS",
                  "shopId":"%s","customerId":null,"vehicleId":null,"invoiceId":null,"parts":[],
                  "services":[],"createdAt":null,"updatedAt":null,"locationId":"%s",
                  "resourceId":null,"resourceType":null,"mechanicIds":[],"promisedAt":null,
                  "scheduledDate":null}}""".formatted(
                        EVENT_ID,
                        WorkorderUpdatedV1.EVENT_TYPE,
                        WORKORDER_ID,
                        UUIDv7Generator.generate(),
                        UUIDv7Generator.generate());
    }

    @TestConfiguration
    static class ThrowingAppointmentSyncConfig {

        @Bean
        @Primary
        ThrowingAppointmentSync throwingAppointmentSync() {
            return new ThrowingAppointmentSync();
        }
    }

    /**
     * A downstream appointment sync that always fails, with the transactional shape the real one
     * had when the defect was found: {@code @Transactional} at default {@code REQUIRED}
     * propagation, so joining the caller's transaction and throwing marks it rollback-only.
     */
    static class ThrowingAppointmentSync implements WorkorderStatusEventService {

        private final AtomicInteger invocations = new AtomicInteger();

        int invocations() {
            return invocations.get();
        }

        @Override
        @Transactional
        public void handleWorkorderStatusChanged(@NonNull WorkorderStatusChangedEvent event) {
            invocations.incrementAndGet();
            throw new IllegalStateException("appointment sync unavailable");
        }
    }
}
