package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.ExtWorkorderPositionReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import com.positivity.shopmanager.internal.repository.WorkOrderAppointmentMappingRepository;
import com.positivity.shopmanager.internal.repository.WorkorderActuals;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The workorder-to-appointment link, written from the owner's fact and nothing else (#2531).
 *
 * <p>{@code work_order_appointment_mapping} had three readers and no writer: only tests inserted
 * rows, so the status sync and the actual-versus-planned read both passed against fixtures and did
 * nothing on a running system. Every test here therefore starts from an appointment and a
 * {@code workorder.workorder.updated} fact, delivered through the real listener against the real
 * repositories and the real after-commit status sync, and never inserts the mapping itself — which
 * is the only shape that can fail if the link goes back to being a table only tests write.
 *
 * <p>Needs real commits (the status sync runs after commit, in its own transaction), so the class
 * is not {@code @Transactional} and cleans up after itself.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Workorder facts link workorders to their appointments (#2531)")
class WorkorderAppointmentLinkIntegrationTest {

    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000002531");
    private static final Instant PLANNED_START = Instant.parse("2026-10-05T15:00:00Z");
    private static final Instant PLANNED_END = Instant.parse("2026-10-05T17:00:00Z");
    private static final Instant ACTUAL_START = Instant.parse("2026-10-05T15:20:00Z");
    private static final Instant ACTUAL_END = Instant.parse("2026-10-05T18:05:00Z");

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private WorkOrderAppointmentMappingRepository mappingRepository;

    @Autowired
    private ExtWorkorderReplicaRepository extWorkorderReplicaRepository;

    @Autowired
    private ExtWorkorderPositionReplicaRepository extWorkorderPositionReplicaRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private WorkorderAppointmentLinkService workorderAppointmentLinkService;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<String> eventIds = new ArrayList<>();
    private UUID workorderId;
    private UUID appointmentId;
    private WorkorderEventsListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        workorderId = UUIDv7Generator.generate();
        appointmentId = appointmentRepository
                .save(Appointment.builder()
                        .status(AppointmentStatus.SCHEDULED)
                        .locationId(LOCATION_ID)
                        .crmCustomerId(UUIDv7Generator.generate())
                        .crmVehicleId(UUIDv7Generator.generate())
                        .startAt(PLANNED_START)
                        .endAt(PLANNED_END)
                        .build())
                .getAppointmentId();
        // Hand-built for the same reason as WorkorderStatusSyncIsolationTest: the @KafkaRails
        // listener bean is not registered on the test profile. Every collaborator is the real one.
        listener = new WorkorderEventsListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEventRepository,
                extWorkorderReplicaRepository,
                extWorkorderPositionReplicaRepository,
                workorderAppointmentLinkService,
                applicationEventPublisher,
                Mockito.mock(ObjectProvider.class),
                transactionManager);
    }

    @AfterEach
    void cleanUp() {
        // The mapping first: it carries the foreign key onto appointment.
        mappingRepository.deleteAll();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> extWorkorderPositionReplicaRepository.deleteAllByWorkorderId(workorderId));
        extWorkorderReplicaRepository.deleteById(workorderId);
        eventIds.forEach(processedEventRepository::deleteById);
        appointmentRepository.deleteById(appointmentId);
    }

    @Test
    @DisplayName(
            "AC1/AC2 - the first fact naming the appointment links the workorder and moves the appointment with it")
    void aFactLinksTheWorkorderAndTheAppointmentFollowsItsStatus() {
        listener.onWorkorderEvent(fact(1, "WORK_IN_PROGRESS", appointmentId, ACTUAL_START, null));

        assertThat(linkedAppointmentOf(workorderId)).isEqualTo(appointmentId);
        // Not an orphaned workorder any more: the status sync found the appointment.
        assertThat(statusOf(appointmentId)).isEqualTo(AppointmentStatus.WORK_IN_PROGRESS);
        assertThat(timelineSizeOf(appointmentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC3 - the appointment's actual window is the linked workorder's, read through the link")
    void theLinkedWorkordersActualsAreReadForTheAppointment() {
        listener.onWorkorderEvent(fact(1, "WORK_IN_PROGRESS", appointmentId, ACTUAL_START, null));
        listener.onWorkorderEvent(fact(2, "COMPLETED", appointmentId, ACTUAL_START, ACTUAL_END));

        List<WorkorderActuals> actuals = mappingRepository.findActualsByAppointmentIds(List.of(appointmentId));
        assertThat(actuals).hasSize(1);
        assertThat(actuals.get(0).workOrderId()).isEqualTo(workorderId);
        assertThat(actuals.get(0).workStartedAt()).isEqualTo(ACTUAL_START);
        assertThat(actuals.get(0).completedAt()).isEqualTo(ACTUAL_END);
        // The second fact repeated the appointment; the workorder still has exactly one link.
        assertThat(mappingRepository.count()).isEqualTo(1);
        assertThat(statusOf(appointmentId)).isEqualTo(AppointmentStatus.QUALITY_CHECK);
        assertThat(timelineSizeOf(appointmentId)).isEqualTo(2);
    }

    @Test
    @DisplayName("review - an appointment cancelled after linking stays cancelled while its workorder goes on")
    void aCancelledAppointmentIsNotRevivedByItsWorkorder() {
        listener.onWorkorderEvent(fact(1, "DRAFT", appointmentId, null, null));
        assertThat(statusOf(appointmentId)).isEqualTo(AppointmentStatus.SCHEDULED);
        inTransaction(() -> {
            Appointment appointment =
                    appointmentRepository.findById(appointmentId).orElseThrow();
            appointment.setStatus(AppointmentStatus.CANCELLED);
            return appointmentRepository.save(appointment);
        });

        listener.onWorkorderEvent(fact(2, "ASSIGNED", appointmentId, null, null));

        assertThat(statusOf(appointmentId)).isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("review - a backfill that first links an already-replicated, completed workorder moves the "
            + "appointment at once")
    void aBackfillThatFirstLinksCatchesTheAppointmentUp() {
        // Replicated before the link was published: no appointmentId, already COMPLETED.
        listener.onWorkorderEvent(fact(4, "COMPLETED", null, ACTUAL_START, ACTUAL_END));
        assertThat(statusOf(appointmentId)).isEqualTo(AppointmentStatus.SCHEDULED);

        // The backfill: same version, same status, now naming the appointment.
        listener.onWorkorderEvent(fact(4, "COMPLETED", appointmentId, ACTUAL_START, ACTUAL_END));

        assertThat(linkedAppointmentOf(workorderId)).isEqualTo(appointmentId);
        assertThat(statusOf(appointmentId)).isEqualTo(AppointmentStatus.QUALITY_CHECK);
    }

    @Test
    @DisplayName("a walk-in's fact writes the replica and no link, and leaves every appointment alone")
    void aWalkInLinksNothing() {
        listener.onWorkorderEvent(fact(1, "WORK_IN_PROGRESS", null, ACTUAL_START, null));

        assertThat(extWorkorderReplicaRepository.existsById(workorderId)).isTrue();
        assertThat(mappingRepository.count()).isZero();
        assertThat(statusOf(appointmentId)).isEqualTo(AppointmentStatus.SCHEDULED);
    }

    @Test
    @DisplayName(
            "a fact naming an appointment this module does not hold still lands its replica row and is marked processed")
    void anUnknownAppointmentDoesNotFailTheReplicaWrite() {
        String fact = fact(1, "WORK_IN_PROGRESS", UUIDv7Generator.generate(), ACTUAL_START, null);

        listener.onWorkorderEvent(fact);

        assertThat(extWorkorderReplicaRepository.existsById(workorderId)).isTrue();
        assertThat(mappingRepository.count()).isZero();
        assertThat(processedEventRepository.existsById(eventIds.get(0))).isTrue();
    }

    @Test
    @DisplayName("#2530 - the bay history lands with the replica row and the next fact replaces it")
    void positionHistoryIsReplicatedAndReplaced() {
        UUID bayOne = UUIDv7Generator.generate();
        UUID bayTwo = UUIDv7Generator.generate();
        listener.onWorkorderEvent(
                factWithPositions(1, "WORK_IN_PROGRESS", positionJson(bayOne, "2026-10-05T15:00:00Z", null)));
        listener.onWorkorderEvent(factWithPositions(
                2,
                "WORK_IN_PROGRESS",
                positionJson(bayOne, "2026-10-05T15:00:00Z", "2026-10-05T16:00:00Z") + ","
                        + positionJson(bayTwo, "2026-10-05T16:00:00Z", null)));

        assertThat(inTransaction(() ->
                        extWorkorderPositionReplicaRepository.findAllByWorkorderIdOrderByAssignedAtAsc(workorderId)))
                .extracting(p -> p.getResourceId(), p -> p.getReleasedAt())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(bayOne, Instant.parse("2026-10-05T16:00:00Z")),
                        org.assertj.core.groups.Tuple.tuple(bayTwo, null));
    }

    private String positionJson(UUID bayId, String assignedAt, String releasedAt) {
        return "{\"resourceType\":\"BAY\",\"resourceId\":\"" + bayId + "\",\"locationId\":\"" + LOCATION_ID
                + "\",\"assignedAt\":\"" + assignedAt + "\",\"releasedAt\":" + json(releasedAt) + "}";
    }

    private String factWithPositions(long version, String status, String positionsJson) {
        String base = fact(version, status, null, ACTUAL_START, null);
        return base.substring(0, base.length() - 2) + ",\"positions\":[" + positionsJson + "]}}";
    }

    private UUID linkedAppointmentOf(UUID workorder) {
        return inTransaction(() -> mappingRepository
                .findByWorkOrderId(workorder)
                .orElseThrow(() -> new AssertionError("no mapping row for workorder " + workorder))
                .getAppointmentId());
    }

    private AppointmentStatus statusOf(UUID appointment) {
        return appointmentRepository.findById(appointment).orElseThrow().getStatus();
    }

    /** The timeline is a lazy collection, so it is counted inside a transaction. */
    private int timelineSizeOf(UUID appointment) {
        return inTransaction(() -> appointmentRepository
                .findById(appointment)
                .orElseThrow()
                .getStatusTimeline()
                .size());
    }

    private <T> T inTransaction(java.util.function.Supplier<T> read) {
        return new TransactionTemplate(transactionManager).execute(status -> read.get());
    }

    private String fact(long version, String status, UUID sourceAppointmentId, Instant startedAt, Instant completedAt) {
        String eventId = UUIDv7Generator.generate().toString();
        eventIds.add(eventId);
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":%d,"payload":{
                  "workorderId":"%s","workorderNumber":"WO-2531-1","status":"%s",
                  "shopId":"%s","customerId":null,"vehicleId":null,"invoiceId":null,"parts":[],
                  "services":[],"createdAt":null,"updatedAt":null,"locationId":"%s",
                  "resourceId":null,"resourceType":null,"mechanicIds":[],"promisedAt":null,
                  "scheduledDate":null,"workStartedAt":%s,"completedAt":%s,"expectedEndAt":null,
                  "appointmentId":%s}}""".formatted(
                        eventId,
                        WorkorderUpdatedV1.EVENT_TYPE,
                        version,
                        workorderId,
                        status,
                        LOCATION_ID,
                        LOCATION_ID,
                        json(startedAt),
                        json(completedAt),
                        json(sourceAppointmentId));
    }

    private static String json(Object value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
