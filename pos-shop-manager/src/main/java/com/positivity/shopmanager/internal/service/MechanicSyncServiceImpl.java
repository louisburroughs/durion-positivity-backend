package com.positivity.shopmanager.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shopmanager.internal.entity.HrIntegrationLog;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.MechanicAuditLog;
import com.positivity.shopmanager.internal.entity.MechanicSkill;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.repository.HrIntegrationLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicAuditLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.MechanicSkillRepository;
import com.positivity.shopmanager.internal.service.dto.HrMechanicEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MechanicSyncServiceImpl implements MechanicSyncService {

    private static final String SYSTEM = "system";

    private final MechanicRepository mechanicRepository;
    private final MechanicSkillRepository mechanicSkillRepository;
    private final HrIntegrationLogRepository hrIntegrationLogRepository;
    private final MechanicAuditLogRepository mechanicAuditLogRepository;
    private final Clock clock;

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
     * pre-check gives the API a 404 where the feed path deliberately no-ops.
     */
    @Override
    @Transactional
    public void replaceSkills(@NonNull String personId, @NonNull List<HrMechanicEvent.Payload.Skill> skills) {
        if (mechanicRepository.findByPersonId(personId).isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Mechanic not found for person " + personId);
        }
        processHrEvent(HrMechanicEvent.builder()
                .eventId(com.positivity.shared.id.UUIDv7Generator.generate())
                .eventType(com.positivity.shopmanager.internal.service.enums.HrEventType.MECHANIC_SKILLS_UPDATED)
                .personId(personId)
                .version(Instant.now(clock).toEpochMilli())
                .occurredAt(Instant.now(clock))
                .payload(HrMechanicEvent.Payload.builder().skills(skills).build())
                .build());
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
