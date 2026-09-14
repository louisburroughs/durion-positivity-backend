package com.positivity.shopmanager.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.entity.HrIntegrationLog;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.MechanicAuditLog;
import com.positivity.shopmanager.internal.entity.MechanicSkill;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.exception.MechanicReplicationPendingException;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.shopmanager.internal.repository.HrIntegrationLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicAuditLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.MechanicSkillRepository;
import com.positivity.shopmanager.internal.service.dto.HrMechanicEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class MechanicSyncServiceImpl implements MechanicSyncService {

    private static final String SYSTEM = "system";

    private static final String TECHNICIAN_ROLE = "TECHNICIAN";
    private static final String ASSIGNMENT_STATUS_ACTIVE = "ACTIVE";

    /** How often the replication wait re-checks for the mechanic row. */
    private static final Duration REPLICATION_POLL_INTERVAL = Duration.ofMillis(250);

    private final MechanicRepository mechanicRepository;
    private final MechanicSkillRepository mechanicSkillRepository;
    private final HrIntegrationLogRepository hrIntegrationLogRepository;
    private final MechanicAuditLogRepository mechanicAuditLogRepository;
    private final ExtStaffingAssignmentReplicaRepository assignmentReplicaRepository;
    private final Clock clock;
    private final Duration replicationWait;

    /**
     * Self-reference (lazy to break the construction cycle) so {@link #replaceSkills} can wait for
     * the mechanic projection outside any transaction and then enter
     * {@link #processHrEvent}'s through the Spring proxy — a self-invocation would run the whole
     * apply with no transaction at all.
     */
    private MechanicSyncService self;

    public MechanicSyncServiceImpl(
            @NonNull MechanicRepository mechanicRepository,
            @NonNull MechanicSkillRepository mechanicSkillRepository,
            @NonNull HrIntegrationLogRepository hrIntegrationLogRepository,
            @NonNull MechanicAuditLogRepository mechanicAuditLogRepository,
            @NonNull ExtStaffingAssignmentReplicaRepository assignmentReplicaRepository,
            @NonNull Clock clock,
            @Value("${pos.shop-manager.mechanic-replication-wait:PT5S}") @NonNull Duration replicationWait) {
        this.mechanicRepository = mechanicRepository;
        this.mechanicSkillRepository = mechanicSkillRepository;
        this.hrIntegrationLogRepository = hrIntegrationLogRepository;
        this.mechanicAuditLogRepository = mechanicAuditLogRepository;
        this.assignmentReplicaRepository = assignmentReplicaRepository;
        this.clock = clock;
        this.replicationWait = replicationWait;
    }

    @Autowired
    public void setSelf(@Lazy MechanicSyncService self) {
        this.self = self;
    }

    @Override
    @Transactional
    public void processHrEvent(@NonNull HrMechanicEvent event) {
        // AC5: validate required fields before any side-effects
        if (event.getPersonId() == null || event.getPersonId().isBlank()) {
            throw new ShopManagerValidationException("personId is required");
        }
        if (event.getVersion() == null) {
            throw new ShopManagerValidationException("version is required");
        }
        if (event.getEventId() == null) {
            throw new ShopManagerValidationException("eventId is required");
        }

        // BR2: idempotency — skip already-processed events
        if (hrIntegrationLogRepository.existsByEventId(event.getEventId())) {
            return;
        }

        // AC4: monotonic ordering — skip stale or equal versions but persist audit
        // trail
        Optional<Mechanic> existing = mechanicRepository.findByPersonId(event.getPersonId());
        if (existing.isPresent() && event.getVersion() <= existing.get().getVersion()) {
            persistIntegrationLog(event, "DISCARDED_STALE");
            return;
        }

        if (event.getEventType() == null) {
            throw new ShopManagerValidationException(
                    "HrMechanicEvent.eventType must not be null, eventId=" + event.getEventId());
        }

        switch (event.getEventType()) {
            case MECHANIC_UPSERTED -> processUpsert(event, existing.orElse(null));
            case MECHANIC_DEACTIVATED -> processDeactivation(event, existing.orElse(null));
            case MECHANIC_SKILLS_UPDATED -> processSkillsUpdate(event, existing.orElse(null));
        }
    }

    private void processUpsert(HrMechanicEvent event, Mechanic existing) {
        String beforeState = existing != null ? existing.toString() : null;
        Mechanic mechanic = existing != null ? updateExistingMechanic(existing, event) : buildNewMechanic(event);

        Mechanic saved = mechanicRepository.save(mechanic);

        // AC3: replace-set skills
        replaceMechanicSkills(saved, event);

        persistAuditLog(event, beforeState, saved.toString());
        persistIntegrationLog(event);
    }

    /** Decides which fields an existing mechanic record carries forward vs. takes from the event. */
    private Mechanic updateExistingMechanic(Mechanic existing, HrMechanicEvent event) {
        applyPayloadFields(existing, event.getPayload());
        existing.setStatus(MechanicStatus.ACTIVE);
        existing.setVersion(event.getVersion());
        existing.setLastSyncedAt(Instant.now(clock));
        return existing;
    }

    /**
     * Applies name/hireDate from an HR payload onto a mechanic, if present. A missing payload
     * leaves the mechanic untouched, and each null field leaves the existing value untouched —
     * a partial HR update must not clear a field it didn't send. The people.events.v1 feed
     * (PeopleEventsListener) never carries names it didn't resolve, so field-level preservation
     * is what keeps replays from erasing mechanic data.
     */
    private void applyPayloadFields(Mechanic mechanic, HrMechanicEvent.Payload payload) {
        if (payload == null) {
            return;
        }
        if (payload.getFirstName() != null) {
            mechanic.setFirstName(payload.getFirstName());
        }
        if (payload.getLastName() != null) {
            mechanic.setLastName(payload.getLastName());
        }
        if (payload.getHireDate() != null) {
            mechanic.setHireDate(payload.getHireDate());
        }
    }

    private Mechanic buildNewMechanic(HrMechanicEvent event) {
        HrMechanicEvent.Payload payload = event.getPayload();
        return Mechanic.builder()
                .personId(event.getPersonId())
                .firstName(payload != null ? payload.getFirstName() : null)
                .lastName(payload != null ? payload.getLastName() : null)
                .hireDate(payload != null ? payload.getHireDate() : null)
                .status(MechanicStatus.ACTIVE)
                .version(event.getVersion())
                .lastSyncedAt(Instant.now(clock))
                .build();
    }

    /**
     * Replace-set semantics scoped to events that actually carry skills: a null skills list
     * means "not sent" and preserves the mechanic's current skill set (the people.events.v1
     * feed never carries skills), while an explicit empty list clears it.
     */
    private void replaceMechanicSkills(Mechanic saved, HrMechanicEvent event) {
        if (event.getPayload() == null || event.getPayload().getSkills() == null) {
            return;
        }
        mechanicSkillRepository.deleteAllByMechanicId(saved.getMechanicId());
        List<MechanicSkill> skills = event.getPayload().getSkills().stream()
                .map(s -> MechanicSkill.builder()
                        .mechanic(saved)
                        .skillCode(s.getSkillCode())
                        .proficiencyLevel(s.getProficiencyLevel())
                        .build())
                .toList();
        mechanicSkillRepository.saveAll(skills);
    }

    private void processDeactivation(HrMechanicEvent event, Mechanic existing) {
        if (existing == null) {
            // Mechanic not found; record the integration log so idempotency guard engages
            // on re-delivery
            persistIntegrationLog(event);
            return;
        }
        String beforeState = existing.toString();
        existing.setStatus(MechanicStatus.INACTIVE);
        existing.setVersion(event.getVersion());
        existing.setLastSyncedAt(Instant.now(clock));
        Mechanic saved = mechanicRepository.save(existing);
        persistAuditLog(event, beforeState, saved.toString());
        persistIntegrationLog(event);
    }

    private void processSkillsUpdate(HrMechanicEvent event, Mechanic existing) {
        if (existing == null) {
            // Mechanic not found; record the integration log so idempotency guard engages
            // on re-delivery
            persistIntegrationLog(event);
            return;
        }
        String beforeState = existing.toString();
        existing.setVersion(event.getVersion());
        existing.setLastSyncedAt(Instant.now(clock));
        Mechanic saved = mechanicRepository.save(existing);

        replaceMechanicSkills(saved, event);

        persistAuditLog(event, beforeState, saved.toString());
        persistIntegrationLog(event);
    }

    @Override
    @Transactional
    public void reconcileFromHr() {
        // HR reconciliation requires a live HR roster client which is not available in
        // this scope.
        // This method must not deactivate mechanics without confirmed HR data.
        // A follow-up story will wire the HR client and implement safe reconciliation.
        throw new UnsupportedOperationException(
                "reconcileFromHr() requires an HR client integration — not yet implemented");
    }

    /**
     * Operator skills edits ride the same HR-feed path as everything else that touches
     * mechanic rows: a synthetic MECHANIC_SKILLS_UPDATED event stamped with a now-millis
     * version, so dedupe, the stale guard, and both logs apply uniformly and ordering
     * against in-flight feed events is last-write-wins by timestamp. The existence
     * pre-check gives the API a refusal where the feed path deliberately no-ops.
     *
     * <p>Deliberately not {@code @Transactional}: {@link #awaitMechanic} waits for a row another
     * thread commits, so it must not run inside a transaction of its own, and the apply is entered
     * through the proxy afterwards so it still gets one.
     */
    @Override
    public void replaceSkills(@NonNull String personId, @NonNull List<HrMechanicEvent.Payload.Skill> skills) {
        awaitMechanic(personId);
        self.processHrEvent(HrMechanicEvent.builder()
                .eventId(com.positivity.shared.id.UUIDv7Generator.generate())
                .eventType(com.positivity.shopmanager.internal.service.enums.HrEventType.MECHANIC_SKILLS_UPDATED)
                .personId(personId)
                .version(Instant.now(clock).toEpochMilli())
                .occurredAt(Instant.now(clock))
                .payload(HrMechanicEvent.Payload.builder().skills(skills).build())
                .build());
    }

    /**
     * Blocks until the person's mechanic row is visible, up to {@code pos.shop-manager
     * .mechanic-replication-wait}, and refuses in a way the caller can act on when it never
     * appears (#1987).
     *
     * <p>Mechanic rows are projected here from ACTIVE TECHNICIAN staffing assignments arriving on
     * {@code people.events.v1}, so a mechanic created moments ago in pos-people is genuinely real
     * and genuinely not here yet. The wait closes that window for the common case — a caller that
     * assigns a technician and immediately sets their skills, the seed and the UI alike — and what
     * it cannot close, it reports honestly: {@link MechanicReplicationPendingException} (503,
     * retry) when this service has no assignment history for the person and so cannot tell an
     * unknown id from an unseen one, and a plain 404 when it does hold that history and none of it
     * makes the person a technician, which no amount of retrying will change.
     *
     * <p>Timed against {@link System#nanoTime()} rather than the injected clock: the deadline is
     * real elapsed time, and a test running on a fixed clock would otherwise never reach it.
     */
    private void awaitMechanic(@NonNull String personId) {
        long deadline = System.nanoTime() + replicationWait.toNanos();
        while (true) {
            if (mechanicRepository.findByPersonId(personId).isPresent()) {
                return;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw mechanicMissing(personId);
            }
            try {
                Thread.sleep(REPLICATION_POLL_INTERVAL);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw mechanicMissing(personId);
            }
        }
    }

    /**
     * Whether a mechanic row that never arrived is a real 404 or replication this service is still
     * waiting on. Only the staffing assignments it already holds can tell the two apart: a person
     * it has assignment history for, none of it an ACTIVE TECHNICIAN assignment, is someone it
     * knows and who is not a mechanic. Anything else — no history, or an id that is not a UUID at
     * all and so cannot be looked up — leaves the question open, and an open question is reported
     * as retryable rather than as an answer.
     */
    private RuntimeException mechanicMissing(@NonNull String personId) {
        List<ExtStaffingAssignmentReplica> assignments = parseUuid(personId)
                .map(assignmentReplicaRepository::findByPersonId)
                .orElseGet(List::of);
        boolean knownNonTechnician = !assignments.isEmpty()
                && assignments.stream()
                        .noneMatch(assignment -> TECHNICIAN_ROLE.equals(assignment.getRole())
                                && ASSIGNMENT_STATUS_ACTIVE.equals(assignment.getStatus()));
        if (knownNonTechnician) {
            return new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "Person " + personId + " is not a mechanic: no active TECHNICIAN assignment");
        }
        log.warn(
                "Mechanic for person {} still not replicated after {}; answering retryable", personId, replicationWait);
        return new MechanicReplicationPendingException(
                personId,
                "Mechanic for person " + personId
                        + " is not visible here yet; the staffing assignment that creates it has not been"
                        + " replicated. Retry shortly.");
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private void persistAuditLog(HrMechanicEvent event, String beforeState, String afterState) {
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        mechanicAuditLogRepository.save(MechanicAuditLog.builder()
                .eventId(event.getEventId())
                .personId(event.getPersonId())
                .eventType(event.getEventType().name())
                .beforeState(beforeState)
                .afterState(afterState)
                .appliedAt(Instant.now(clock))
                .changedBy(actor)
                .build());
    }

    private void persistIntegrationLog(HrMechanicEvent event) {
        persistIntegrationLog(event, "PROCESSED");
    }

    private void persistIntegrationLog(HrMechanicEvent event, String status) {
        hrIntegrationLogRepository.save(HrIntegrationLog.builder()
                .eventId(event.getEventId())
                .personId(event.getPersonId())
                .eventType(event.getEventType() != null ? event.getEventType().name() : "UNKNOWN")
                .receivedAt(Instant.now(clock))
                .processedAt(Instant.now(clock))
                .status(status)
                .build());
    }
}
