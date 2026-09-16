package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.AppointmentConflictView;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.SchedulingConflict;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.ConflictSeverity;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.repository.ConflictOverrideRepository;
import com.positivity.shopmanager.internal.repository.SchedulingConflictRepository;
import com.positivity.shopmanager.internal.service.SchedulingConflictEvaluator.BookingAttempt;
import com.positivity.shopmanager.internal.service.SchedulingConflictEvaluator.DetectedConflict;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and reads {@code scheduling_conflict} for the booking path (CAP-326). Its {@code
 * REQUIRES_NEW} methods exist because of the exclusion constraint: once PostgreSQL has refused an
 * insert, the booking transaction is aborted and can neither commit nor read, so the refusal's own
 * record — and the re-query that tells a race from a double-booking — must run on a fresh
 * connection. {@code REQUIRES_NEW} only applies across a Spring proxy, which is why this is a
 * separate bean and not a private method of the service.
 */
@Service
@RequiredArgsConstructor
public class SchedulingConflictRecorder {

    private final SchedulingConflictRepository schedulingConflictRepository;
    private final ConflictOverrideRepository conflictOverrideRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentServiceRequestRepository appointmentServiceRequestRepository;
    private final SchedulingConflictEvaluator evaluator;
    private final Clock clock;

    /**
     * Records the conflicts a refused attempt raised. {@code appointment_id} is the appointment
     * being rescheduled when there is one, otherwise NULL: nothing was booked. Committed on its own
     * so the record survives the refusal that follows.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRefused(@NonNull BookingAttempt attempt, @NonNull List<DetectedConflict> conflicts) {
        Appointment rescheduling = attempt.excludeAppointmentId() == null
                ? null
                : appointmentRepository.findById(attempt.excludeAppointmentId()).orElse(null);
        Instant now = Instant.now(clock);
        for (DetectedConflict conflict : conflicts) {
            schedulingConflictRepository.save(row(conflict, attempt, rescheduling, now));
        }
    }

    /** The exclusion constraint refused the insert: record BAY_DOUBLE_BOOKED and return it for the envelope. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public @NonNull DetectedConflict recordRefusedOverlap(@NonNull BookingAttempt attempt) {
        DetectedConflict conflict = evaluator.bayDoubleBooked(attempt);
        recordRefused(attempt, List.of(conflict));
        return conflict;
    }

    /** Records the SOFT conflicts a booking proceeded under, in the booking's own transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull List<SchedulingConflict> recordAccepted(
            @NonNull Appointment appointment, @NonNull List<DetectedConflict> conflicts) {
        Instant now = Instant.now(clock);
        List<SchedulingConflict> saved = new ArrayList<>(conflicts.size());
        for (DetectedConflict conflict : conflicts) {
            if (conflict.isHard()) {
                throw new IllegalStateException("A HARD conflict is never recorded against a booked appointment");
            }
            BookingAttempt attempt = new BookingAttempt(
                    appointment.getLocationId(),
                    appointment.getResourceId(),
                    appointment.getStartAt(),
                    appointment.getEndAt(),
                    appointment.getAppointmentId());
            saved.add(schedulingConflictRepository.save(row(conflict, attempt, appointment, now)));
        }
        return saved;
    }

    /**
     * An appointment identical to the request in everything but the key (spec D17 item 3): same
     * location, resource, customer, vehicle, window, workorder link and service requests, still
     * holding its slot. On a fresh connection so it can also answer inside the {@code 23P01} path.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Appointment> findKeylessDuplicate(@NonNull AppointmentCreateRequest request) {
        return appointmentRepository
                .findHeldOverlappingAtLocation(
                        request.getLocationId(),
                        request.getStartAt(),
                        request.getEndAt(),
                        AppointmentStatus.holdingAResource())
                .stream()
                .filter(existing -> Objects.equals(existing.getResourceId(), request.getResourceId())
                        && Objects.equals(existing.getCrmCustomerId(), request.getCrmCustomerId())
                        && Objects.equals(existing.getCrmVehicleId(), request.getCrmVehicleId())
                        && Objects.equals(existing.getStartAt(), request.getStartAt())
                        && Objects.equals(existing.getEndAt(), request.getEndAt())
                        && Objects.equals(existing.getWorkorderLinkRef(), request.getWorkorderLinkRef())
                        && Objects.equals(
                                serviceRequestIdsOf(existing.getAppointmentId()),
                                normalize(request.getServiceRequestIds())))
                .min(Comparator.comparing(Appointment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    /** The conflicts recorded against an appointment, with whether each has been overridden. */
    @Transactional(readOnly = true)
    public @NonNull List<AppointmentConflictView> viewsFor(@NonNull UUID appointmentId) {
        return schedulingConflictRepository.findByAppointment_AppointmentId(appointmentId).stream()
                .sorted(Comparator.comparing(SchedulingConflict::getDetectedAt))
                .map(conflict -> {
                    boolean overridden = conflictOverrideRepository.existsByConflict_Id(conflict.getId());
                    return AppointmentConflictView.builder()
                            .conflictId(conflict.getId())
                            .code(conflict.getConflictRule().getCode())
                            .severity(conflict.getSeverity().name())
                            .message(conflict.getDetail())
                            .resourceId(conflict.getResourceId())
                            .overridable(conflict.getSeverity() == ConflictSeverity.SOFT && !overridden)
                            .overridden(overridden)
                            .build();
                })
                .toList();
    }

    private static SchedulingConflict row(
            DetectedConflict conflict, BookingAttempt attempt, Appointment appointment, Instant now) {
        return SchedulingConflict.builder()
                .appointment(appointment)
                .conflictRule(conflict.rule())
                .severity(conflict.severity())
                .locationId(attempt.locationId())
                .resourceId(conflict.resourceId())
                .attemptedStartAt(attempt.startAt())
                .attemptedEndAt(attempt.endAt())
                .detail(conflict.detail())
                .detectedAt(now)
                .build();
    }

    private List<UUID> serviceRequestIdsOf(UUID appointmentId) {
        return normalize(appointmentServiceRequestRepository.findByAppointment_AppointmentId(appointmentId).stream()
                .map(entry -> entry.getServiceEntityId())
                .toList());
    }

    private static List<UUID> normalize(List<UUID> ids) {
        return ids == null
                ? List.of()
                : ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }
}
