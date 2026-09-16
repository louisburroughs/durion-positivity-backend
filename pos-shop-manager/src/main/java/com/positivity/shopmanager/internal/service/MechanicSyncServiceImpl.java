package com.positivity.shopmanager.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shopmanager.internal.entity.HrIntegrationLog;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.MechanicAuditLog;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.repository.HrIntegrationLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicAuditLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.service.dto.HrMechanicEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects the mechanic roster from HR facts (today: TECHNICIAN staffing assignments relayed by
 * {@link PeopleEventsListener}). Idempotent on event id, monotonic on the HR version, and audited
 * in both the integration and mechanic logs.
 *
 * <p>Competence is not this service's concern (CAP-328): a person's credentials are the People
 * domain's aggregate, replicated into {@code ext_person_credential} by the same listener. The
 * mechanic row carries identity and employment state only.
 */
@Slf4j
@Service
public class MechanicSyncServiceImpl implements MechanicSyncService {

    private static final String SYSTEM = "system";

    private final MechanicRepository mechanicRepository;
    private final HrIntegrationLogRepository hrIntegrationLogRepository;
    private final MechanicAuditLogRepository mechanicAuditLogRepository;
    private final Clock clock;

    public MechanicSyncServiceImpl(
            @NonNull MechanicRepository mechanicRepository,
            @NonNull HrIntegrationLogRepository hrIntegrationLogRepository,
            @NonNull MechanicAuditLogRepository mechanicAuditLogRepository,
            @NonNull Clock clock) {
        this.mechanicRepository = mechanicRepository;
        this.hrIntegrationLogRepository = hrIntegrationLogRepository;
        this.mechanicAuditLogRepository = mechanicAuditLogRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void processHrEvent(@NonNull HrMechanicEvent event) {
        if (event.getPersonId() == null || event.getPersonId().isBlank()) {
            throw new ShopManagerValidationException("personId is required");
        }
        UUID personId = requireResolvablePersonId(event.getPersonId());
        if (event.getVersion() == null) {
            throw new ShopManagerValidationException("version is required");
        }
        if (event.getEventId() == null) {
            throw new ShopManagerValidationException("eventId is required");
        }

        // Idempotency guard: an already-processed eventId is a no-op.
        if (hrIntegrationLogRepository.existsByEventId(event.getEventId())) {
            return;
        }

        Optional<Mechanic> existing = mechanicRepository.findByPersonId(personId);

        // Monotonic ordering: a version at or below the stored one is a stale replay and is
        // discarded (recorded as DISCARDED_STALE) rather than applied.
        if (existing.isPresent() && event.getVersion() <= existing.get().getVersion()) {
            persistIntegrationLog(event, "DISCARDED_STALE");
            return;
        }

        if (event.getEventType() == null) {
            throw new ShopManagerValidationException(
                    "HrMechanicEvent.eventType must not be null, eventId=" + event.getEventId());
        }

        switch (event.getEventType()) {
            case MECHANIC_UPSERTED -> processUpsert(event, personId, existing.orElse(null));
            case MECHANIC_DEACTIVATED -> processDeactivation(event, existing.orElse(null));
        }
    }

    private void processUpsert(HrMechanicEvent event, UUID personId, Mechanic existing) {
        String beforeState = existing != null ? existing.toString() : null;
        Mechanic mechanic =
                existing != null ? updateExistingMechanic(existing, event) : buildNewMechanic(event, personId);
        Mechanic saved = mechanicRepository.save(mechanic);
        persistAuditLog(event, beforeState, saved.toString());
        persistIntegrationLog(event);
    }

    private Mechanic updateExistingMechanic(Mechanic existing, HrMechanicEvent event) {
        applyPayloadFields(existing, event.getPayload());
        existing.setStatus(MechanicStatus.ACTIVE);
        existing.setVersion(event.getVersion());
        existing.setLastSyncedAt(Instant.now(clock));
        return existing;
    }

    /** Fields the event does not carry are preserved: the feed sends what changed, not a snapshot. */
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

    private Mechanic buildNewMechanic(HrMechanicEvent event, UUID personId) {
        HrMechanicEvent.Payload payload = event.getPayload();
        return Mechanic.builder()
                .personId(personId)
                .firstName(payload != null ? payload.getFirstName() : null)
                .lastName(payload != null ? payload.getLastName() : null)
                .hireDate(payload != null ? payload.getHireDate() : null)
                .status(MechanicStatus.ACTIVE)
                .version(event.getVersion())
                .lastSyncedAt(Instant.now(clock))
                .build();
    }

    /** A deactivation for a person who never was a mechanic is logged and otherwise a no-op. */
    private void processDeactivation(HrMechanicEvent event, Mechanic existing) {
        if (existing == null) {
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

    @Override
    @Transactional
    public void reconcileFromHr() {
        throw new UnsupportedOperationException(
                "reconcileFromHr() requires an HR client integration — not yet implemented");
    }

    /**
     * The person id is the People domain's UUID, the one identity this module has for a person
     * (CAP-328). A value that is not one can never name a person on this platform, so it is
     * refused before any lookup or write.
     */
    private static UUID requireResolvablePersonId(@NonNull String personId) {
        try {
            return UUID.fromString(personId.trim());
        } catch (IllegalArgumentException notAUuid) {
            throw new ShopManagerValidationException("personId is not a UUID: " + personId);
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
