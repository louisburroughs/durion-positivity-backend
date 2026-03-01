package com.positivity.shopmanager.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shopmanager.internal.entity.HrIntegrationLog;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.MechanicAuditLog;
import com.positivity.shopmanager.internal.entity.MechanicSkill;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.repository.HrIntegrationLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicAuditLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.MechanicSkillRepository;
import com.positivity.shopmanager.service.MechanicSyncService;
import com.positivity.shopmanager.service.dto.HrMechanicEvent;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

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
            throw new IllegalArgumentException("personId is required");
        }
        if (event.getVersion() == null) {
            throw new IllegalArgumentException("version is required");
        }
        if (event.getEventId() == null) {
            throw new IllegalArgumentException("eventId is required");
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
            throw new IllegalArgumentException(
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
        Mechanic mechanic;

        if (existing != null) {
            if (event.getPayload() != null) {
                existing.setFirstName(event.getPayload().getFirstName());
                existing.setLastName(event.getPayload().getLastName());
                if (event.getPayload().getHireDate() != null) {
                    existing.setHireDate(event.getPayload().getHireDate());
                }
            }
            existing.setStatus(MechanicStatus.ACTIVE);
            existing.setVersion(event.getVersion());
            existing.setLastSyncedAt(Instant.now(clock));
            mechanic = existing;
        } else {
            mechanic = Mechanic.builder()
                    .personId(event.getPersonId())
                    .firstName(event.getPayload() != null ? event.getPayload().getFirstName() : null)
                    .lastName(event.getPayload() != null ? event.getPayload().getLastName() : null)
                    .hireDate(event.getPayload() != null ? event.getPayload().getHireDate() : null)
                    .status(MechanicStatus.ACTIVE)
                    .version(event.getVersion())
                    .lastSyncedAt(Instant.now(clock))
                    .build();
        }

        Mechanic saved = mechanicRepository.save(mechanic);

        // AC3: replace-set skills
        mechanicSkillRepository.deleteAllByMechanicId(saved.getMechanicId());
        if (event.getPayload() != null && event.getPayload().getSkills() != null) {
            List<MechanicSkill> skills = event.getPayload().getSkills().stream()
                    .map(s -> MechanicSkill.builder()
                            .mechanicId(saved.getMechanicId())
                            .skillCode(s.getSkillCode())
                            .proficiencyLevel(s.getProficiencyLevel())
                            .build())
                    .toList();
            mechanicSkillRepository.saveAll(skills);
        }

        persistAuditLog(event, beforeState, saved.toString());
        persistIntegrationLog(event);
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

        mechanicSkillRepository.deleteAllByMechanicId(saved.getMechanicId());
        if (event.getPayload() != null && event.getPayload().getSkills() != null) {
            List<MechanicSkill> skills = event.getPayload().getSkills().stream()
                    .map(s -> MechanicSkill.builder()
                            .mechanicId(saved.getMechanicId())
                            .skillCode(s.getSkillCode())
                            .proficiencyLevel(s.getProficiencyLevel())
                            .build())
                    .toList();
            mechanicSkillRepository.saveAll(skills);
        }

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
