package com.positivity.shopmanager.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shopmanager.internal.dto.ConflictResponse;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.ConflictOverride;
import com.positivity.shopmanager.internal.entity.SchedulingConflict;
import com.positivity.shopmanager.internal.enums.ConflictSeverity;
import com.positivity.shopmanager.internal.exception.AppointmentNotFoundException;
import com.positivity.shopmanager.internal.exception.ConflictOverrideStateException;
import com.positivity.shopmanager.internal.exception.SchedulingConflictException;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.ConflictOverrideRepository;
import com.positivity.shopmanager.internal.repository.SchedulingConflictRepository;
import com.positivity.shopmanager.internal.security.LocationScopeGuard;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.dto.ConflictOverrideRequest;
import com.positivity.shopmanager.internal.service.dto.ConflictOverrideResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The override half of DECISION-SHOPMGMT-002 (CAP-326). The conflict rows already exist — the
 * booking path wrote them when it warned and allowed (spec D10) — so this only has to prove the
 * caller may accept them and then write the immutable record that they did.
 *
 * <p>Refusals, in order: a malformed request is 400; an appointment the replica does not know is
 * 404; a conflict not recorded against this appointment is 400 (the id is the client's error, not a
 * state); a HARD conflict is 409 with the DECISION-002 envelope and {@code overridable:false}, and
 * <em>no row is written</em> — that is what keeps the record's audit ("HARD conflicts with
 * overrides; should be zero") true by construction; a conflict already overridden is 409 {@code
 * CONFLICT_ALREADY_OVERRIDDEN}. All-or-nothing: one refused conflict refuses the request.
 */
@Service
@RequiredArgsConstructor
public class ConflictOverrideServiceImpl implements ConflictOverrideService {

    static final String SYSTEM = "system";
    static final String ERROR_CODE = "SCHEDULING_CONFLICT";

    private final AppointmentRepository appointmentRepository;
    private final SchedulingConflictRepository schedulingConflictRepository;
    private final ConflictOverrideRepository conflictOverrideRepository;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull ConflictOverrideResponse execute(
            @NonNull UUID appointmentId, @NonNull ConflictOverrideRequest request) {
        if (request.getOverrideReason() == null || request.getOverrideReason().isBlank()) {
            throw new ShopManagerValidationException("overrideReason must not be blank");
        }
        if (request.getConflictIds() == null || request.getConflictIds().isEmpty()) {
            throw new ShopManagerValidationException("conflictIds must name at least one conflict");
        }

        Appointment appointment = appointmentRepository
                .findById(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(appointmentId));
        // The permission says who may override; the appointment's location says where
        // (ADR-0061, DECISION-SHOPMGMT-012, spec D12).
        LocationScopeGuard.requireAny(appointment.getLocationId(), ShopPermissions.CONFLICT_OVERRIDE);

        List<SchedulingConflict> conflicts = resolveConflictsOf(appointmentId, request.getConflictIds());

        List<SchedulingConflict> hard = conflicts.stream()
                .filter(conflict -> conflict.getSeverity() == ConflictSeverity.HARD)
                .toList();
        if (!hard.isEmpty()) {
            throw new SchedulingConflictException(envelope(hard));
        }
        for (SchedulingConflict conflict : conflicts) {
            if (conflictOverrideRepository.existsByConflict_Id(conflict.getId())) {
                throw new ConflictOverrideStateException(
                        "Conflict " + conflict.getId() + " already carries an override");
            }
        }

        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        Instant now = Instant.now(clock);
        List<ConflictOverrideResponse.OverrideEntry> entries = new ArrayList<>(conflicts.size());
        for (SchedulingConflict conflict : conflicts) {
            ConflictOverride saved = conflictOverrideRepository.save(ConflictOverride.builder()
                    .conflict(conflict)
                    .overriddenBy(actor)
                    .overrideReason(request.getOverrideReason())
                    .approvedBy(actor)
                    .approvedAt(now)
                    .createdAt(now)
                    .build());
            entries.add(ConflictOverrideResponse.OverrideEntry.builder()
                    .overrideId(saved.getId())
                    .conflictId(conflict.getId())
                    .ruleCode(conflict.getConflictRule().getCode())
                    .severity(conflict.getSeverity().name())
                    .build());
        }
        return ConflictOverrideResponse.builder()
                .appointmentId(appointmentId)
                .overriddenBy(actor)
                .approvedAt(now)
                .overrideReason(request.getOverrideReason())
                .overrides(entries)
                .build();
    }

    /** The named conflicts, in request order, each proven to be recorded against this appointment. */
    private List<SchedulingConflict> resolveConflictsOf(UUID appointmentId, List<UUID> requestedIds) {
        Set<UUID> ids = new LinkedHashSet<>(requestedIds);
        Map<UUID, SchedulingConflict> found = schedulingConflictRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(SchedulingConflict::getId, Function.identity()));
        List<SchedulingConflict> ordered = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            SchedulingConflict conflict = found.get(id);
            boolean belongs = conflict != null
                    && conflict.getAppointment() != null
                    && Objects.equals(conflict.getAppointment().getAppointmentId(), appointmentId);
            if (!belongs) {
                throw new ShopManagerValidationException(
                        "Conflict " + id + " is not recorded against appointment " + appointmentId);
            }
            ordered.add(conflict);
        }
        return ordered;
    }

    /** DECISION-002's envelope for the HARD conflicts a caller tried to override. */
    private ConflictResponse envelope(List<SchedulingConflict> hard) {
        List<ConflictResponse.Conflict> conflicts = hard.stream()
                .map(conflict -> new ConflictResponse.Conflict(
                        conflict.getSeverity().name(),
                        conflict.getConflictRule().getCode(),
                        conflict.getDetail(),
                        false,
                        conflict.getResourceId()))
                .toList();
        return new ConflictResponse(
                ERROR_CODE,
                "HARD conflicts cannot be overridden; " + hard.size() + " of the named conflicts are HARD",
                null,
                Instant.now(clock),
                conflicts);
    }
}
