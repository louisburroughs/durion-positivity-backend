package com.positivity.shopmanager.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shopmanager.internal.entity.HrIntegrationLog;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.MechanicAuditLog;
import com.positivity.shopmanager.internal.entity.MechanicSkill;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.exception.MechanicReplicationPendingException;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.repository.HrIntegrationLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicAuditLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.MechanicSkillRepository;
import com.positivity.shopmanager.internal.service.dto.HrMechanicEvent;
import com.positivity.shopmanager.internal.service.enums.HrEventType;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class MechanicSyncServiceImpl implements MechanicSyncService {

    private static final String SYSTEM = "system";

    /** How often the replication wait re-checks for the mechanic row. */
    private static final Duration REPLICATION_POLL_INTERVAL = Duration.ofMillis(250);

    private final MechanicRepository mechanicRepository;
    private final MechanicSkillRepository mechanicSkillRepository;
    private final HrIntegrationLogRepository hrIntegrationLogRepository;
    private final MechanicAuditLogRepository mechanicAuditLogRepository;
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
            @NonNull Clock clock,
            @Value("${pos.shop-manager.mechanic-replication-wait:PT5S}") @NonNull Duration replicationWait) {
        this.mechanicRepository = mechanicRepository;
        this.mechanicSkillRepository = mechanicSkillRepository;
        this.hrIntegrationLogRepository = hrIntegrationLogRepository;
        this.mechanicAuditLogRepository = mechanicAuditLogRepository;
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
        // trail. Operator edits are exempt: they carry an epoch-millis stamp rather than a
        // position in the feed's per-aggregate sequence, so comparing the two is meaningless in
        // both directions (#1987).
        Optional<Mechanic> existing = mechanicRepository.findByPersonId(event.getPersonId());
        if (!event.isOperatorEdit()
                && existing.isPresent()
                && event.getVersion() <= existing.get().getVersion()) {
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
        // An operator edit must not advance the feed-ordering version. It is stamped with
        // epoch-millis (~1.7e12) while feed events carry the producer's aggregateVersion (1, 2,
        // 3…), so writing it through would leave every later people.events.v1 event for this
        // mechanic — a name refresh, the deactivation when their assignment ends — below the
        // stored version and silently discarded as DISCARDED_STALE (#1987). Skills are
        // shop-manager-owned enrichment the feed never carries, so the two orderings are
        // independent and only the feed's belongs in this column.
        if (!event.isOperatorEdit()) {
            existing.setVersion(event.getVersion());
        }
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

    /** {@inheritDoc} */
    @Override
    public void replaceSkills(@NonNull String personId, @NonNull List<HrMechanicEvent.Payload.Skill> skills) {
        replaceSkills(personId, skills, true);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deliberately {@code NOT_SUPPORTED} rather than merely un-annotated: {@link #awaitMechanic}
     * waits for a row another thread commits, so it must not run inside a transaction — not its
     * own, and not one an enclosing caller opened, which would hold a pooled connection asleep for
     * the whole window. Suspending makes the invariant enforced instead of documented. The apply is
     * then entered through the proxy, which starts the transaction it does need.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void replaceSkills(
            @NonNull String personId, @NonNull List<HrMechanicEvent.Payload.Skill> skills, boolean awaitReplication) {
        requireResolvablePersonId(personId);
        awaitMechanic(personId, awaitReplication);
        self.processHrEvent(HrMechanicEvent.builder()
                .eventId(UUIDv7Generator.generate())
                .eventType(HrEventType.MECHANIC_SKILLS_UPDATED)
                .personId(personId)
                .version(Instant.now(clock).toEpochMilli())
                .occurredAt(Instant.now(clock))
                .operatorEdit(true)
                .payload(HrMechanicEvent.Payload.builder().skills(skills).build())
                .build());
    }

    /**
     * The one negative about a person this service can actually prove (#1987).
     *
     * <p>Every person id on this platform is a UUID (ADR-0027), so an id that is not one names
     * nobody and never will — no amount of replication produces a mechanic for it. Checked before
     * the wait, so a typo in a CSV costs nothing and is reported as the client error it is rather
     * than spending the window and then being told to try again forever.
     */
    private void requireResolvablePersonId(@NonNull String personId) {
        try {
            UUID.fromString(personId);
        } catch (IllegalArgumentException notAUuid) {
            throw new ShopManagerValidationException("personId is not a UUID: " + personId);
        }
    }

    /**
     * Blocks until the person's mechanic row is visible, up to {@code pos.shop-manager
     * .mechanic-replication-wait}, and refuses in a way the caller can act on when it never
     * appears (#1987).
     *
     * <p>Mechanic rows are projected here from ACTIVE TECHNICIAN staffing assignments arriving on
     * {@code people.events.v1}, so a mechanic created moments ago in pos-people is genuinely real
     * and genuinely not here yet. The wait closes that window for a caller that assigns a
     * technician and immediately sets their skills.
     *
     * <p>What it cannot close it does not pretend to answer. This service holds no signal that its
     * replica is current — Kafka orders per aggregate, not globally, and the producer's outbox may
     * not have published at all — so a missing mechanic row is always reported as
     * {@link MechanicReplicationPendingException}, never as a 404. An earlier revision inferred
     * "this person is not a technician" from the staffing-assignment replica holding no ACTIVE
     * TECHNICIAN row for them; that replica is an event-fed history with no completeness
     * guarantee, so a person with older non-technician assignments and a brand-new technician one
     * still in flight was told, non-retryably, that they were not a mechanic — this issue's own
     * ambiguity, one branch over.
     *
     * <p>Timed against {@link System#nanoTime()} rather than the injected clock: the deadline is
     * real elapsed time, and a test running on a fixed clock would otherwise never reach it.
     */
    private void awaitMechanic(@NonNull String personId, boolean awaitReplication) {
        long deadline = System.nanoTime() + (awaitReplication ? replicationWait.toNanos() : 0L);
        while (true) {
            if (mechanicRepository.findByPersonId(personId).isPresent()) {
                return;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw mechanicPending(personId, awaitReplication);
            }
            try {
                Thread.sleep(REPLICATION_POLL_INTERVAL);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw mechanicPending(personId, awaitReplication);
            }
        }
    }

    private MechanicReplicationPendingException mechanicPending(@NonNull String personId, boolean waited) {
        log.warn(
                "No mechanic for person {} (waited={}); answering retryable",
                personId,
                waited ? replicationWait : Duration.ZERO);
        return new MechanicReplicationPendingException(
                personId,
                "No mechanic for person " + personId
                        + " here yet. Either the staffing assignment that creates one has not"
                        + " replicated, or the person holds no active TECHNICIAN assignment; this"
                        + " service cannot tell the two apart. Retry shortly.");
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
