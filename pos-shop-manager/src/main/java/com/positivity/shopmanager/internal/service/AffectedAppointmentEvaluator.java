package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.AppointmentServiceRequest;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtCatalogServiceReplica;
import com.positivity.shopmanager.internal.entity.ExtMobileUnitReplica;
import com.positivity.shopmanager.internal.entity.ExtVehicleReplica;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.ResourceType;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtVehicleReplicaRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * DECISION-SHOPMGMT-022: derives, at read time, which appointments are <em>affected</em> by a
 * resource that has left service, been retired, or lost the eligibility its booking relied on.
 * Nothing is stored — a resource returning to service (or regaining eligibility) clears the flag
 * by itself the next time this runs.
 *
 * <p>An appointment is affected when <b>all</b> of the following hold:
 *
 * <ul>
 *   <li>its status is one of {@link #HELD_PRE_WORK_STATUSES} — "held" (not yet cancelled or
 *       completed) <em>and</em> not yet started. Of {@link AppointmentStatus}'s pre-terminal
 *       values, only {@code SCHEDULED} qualifies: {@code CHECKED_IN}, {@code WORK_IN_PROGRESS},
 *       {@code WAITING_FOR_PARTS}, {@code QUALITY_CHECK} and {@code READY_FOR_PICKUP} all mean the
 *       visit is already under way, so moving the vehicle to another bay is a shop-floor
 *       reassignment, not a reschedule-queue item; {@code REOPENED} is a post-completion status,
 *       equally not pre-work. There is no {@code CONFIRMED} status in this enum, so {@code
 *       SCHEDULED} is the whole set.
 *   <li>its {@code startAt} is in the future, per the injected {@link Clock} — a resource going
 *       out of service does not retroactively affect a visit that already happened.
 *   <li>its {@code resourceType} is {@code BAY} or {@code MOBILE_UNIT} — not {@code UNASSIGNED} and
 *       not the legacy {@link SchedulingConflictEvaluator#TECHNICIAN_RESOURCE_TYPE} reading, since
 *       neither names a bay or mobile-unit resource this decision is about.
 *   <li>its named resource is missing from the replica, is not {@code ACTIVE}, or (a {@code BAY}
 *       only) no longer passes {@link BayEligibilityService#refusalFor}.
 * </ul>
 *
 * <p>This module's {@code ext_bay} / {@code ext_mobile_unit} replicas collapse {@code
 * OUT_OF_SERVICE} and {@code RETIRED} into one {@code active = false} row (see {@code
 * LocationEventsListener#isActiveStatus}) rather than keeping the owner's status text, so "not
 * ACTIVE" here already covers both conditions the decision names; there is no further distinction
 * to make from this replica. A mobile unit runs existence and active checks only, the same subset
 * {@link AppointmentsServiceImpl#resolveAndValidateResourceType} applies to it at submit and
 * reschedule (DECISION-SHOPMGMT-023: mobile scheduling, and so a cheap per-unit eligibility check
 * beyond status, has not landed).
 *
 * <p>Built for batch list reads (the schedule view, and the reschedule queue filter): {@link
 * #evaluate} loads every replica, service-request and vehicle row its candidates need in one query
 * each, never once per appointment. {@link #evaluateOne} is the single-appointment convenience for
 * a single-resource read (getById) and for capturing a reschedule's own affected state before any
 * field of it changes (DECISION-SHOPMGMT-004's shop-caused exemption).
 */
@Service
@RequiredArgsConstructor
public class AffectedAppointmentEvaluator {

    /** See the class doc: the only "held, not yet started" status in {@link AppointmentStatus}. */
    private static final Set<AppointmentStatus> HELD_PRE_WORK_STATUSES = EnumSet.of(AppointmentStatus.SCHEDULED);

    private final ExtBayReplicaRepository bayReplicaRepository;
    private final ExtMobileUnitReplicaRepository mobileUnitReplicaRepository;
    private final ExtCatalogServiceReplicaRepository catalogServiceRepository;
    private final ExtVehicleReplicaRepository vehicleReplicaRepository;
    private final AppointmentServiceRequestRepository appointmentServiceRequestRepository;
    private final BayEligibilityService bayEligibilityService;
    private final Clock clock;

    /** {@link #evaluate} for exactly one appointment. */
    public boolean evaluateOne(@NonNull Appointment appointment) {
        return evaluate(appointment.getLocationId(), List.of(appointment))
                .getOrDefault(appointment.getAppointmentId(), false);
    }

    /**
     * Every appointment in {@code appointments} (all at {@code locationId}), mapped to whether it
     * is DECISION-SHOPMGMT-022 affected. Every entry of {@code appointments} gets an entry in the
     * result, including {@code false} for one that is not a candidate at all.
     */
    public @NonNull Map<UUID, Boolean> evaluate(@NonNull UUID locationId, @NonNull List<Appointment> appointments) {
        Map<UUID, Boolean> result = new LinkedHashMap<>();
        List<Appointment> bayCandidates = new ArrayList<>();
        List<Appointment> mobileCandidates = new ArrayList<>();
        Instant now = Instant.now(clock);

        for (Appointment appointment : appointments) {
            ResourceType candidateType = candidateResourceType(appointment);
            if (candidateType == null || !isHeldAndFuture(appointment, now)) {
                result.put(appointment.getAppointmentId(), false);
                continue;
            }
            if (candidateType == ResourceType.BAY) {
                bayCandidates.add(appointment);
            } else {
                mobileCandidates.add(appointment);
            }
        }

        if (bayCandidates.isEmpty() && mobileCandidates.isEmpty()) {
            return result;
        }

        Map<UUID, ExtBayReplica> bayById =
                loadById(bayCandidates, bayReplicaRepository::findAllById, ExtBayReplica::getBayId);
        Map<UUID, ExtMobileUnitReplica> mobileUnitById = loadById(
                mobileCandidates, mobileUnitReplicaRepository::findAllById, ExtMobileUnitReplica::getMobileUnitId);

        // The location's own active bays, once (BayEligibilityService's specialty-map fallback
        // needs the same roster the opening search and submit-time validation use).
        List<ExtBayReplica> locationBays =
                bayCandidates.isEmpty() ? List.of() : bayReplicaRepository.findActiveByLocationOrdered(locationId);

        Map<UUID, List<UUID>> serviceRequestIdsByAppointment = loadServiceRequestIds(bayCandidates);
        Map<UUID, String> operationCodeByServiceId = loadOperationCodes(serviceRequestIdsByAppointment);
        Map<UUID, Integer> gvwrClassByVehicle = loadGvwrClasses(bayCandidates);

        for (Appointment appointment : bayCandidates) {
            boolean affected = isBayAffected(
                    appointment,
                    bayById,
                    locationBays,
                    serviceRequestIdsByAppointment.getOrDefault(appointment.getAppointmentId(), List.of()),
                    operationCodeByServiceId,
                    gvwrClassByVehicle.get(appointment.getCrmVehicleId()));
            result.put(appointment.getAppointmentId(), affected);
        }
        for (Appointment appointment : mobileCandidates) {
            result.put(appointment.getAppointmentId(), isMobileUnitAffected(appointment, mobileUnitById));
        }
        return result;
    }

    /**
     * The candidate resource axis for {@code appointment}, or {@code null} when it names no bay or
     * mobile-unit resource at all: no stored {@code resourceType}, {@code UNASSIGNED}, the legacy
     * {@code TECHNICIAN} reading, or any other value {@link ResourceType} does not recognise.
     */
    private static @Nullable ResourceType candidateResourceType(Appointment appointment) {
        String stored = appointment.getResourceType();
        if (stored == null) {
            return null;
        }
        try {
            ResourceType type = ResourceType.valueOf(stored);
            return type == ResourceType.UNASSIGNED ? null : type;
        } catch (IllegalArgumentException notARecognisedValue) {
            return null;
        }
    }

    private static boolean isHeldAndFuture(Appointment appointment, Instant now) {
        return HELD_PRE_WORK_STATUSES.contains(appointment.getStatus())
                && appointment.getStartAt() != null
                && appointment.getStartAt().isAfter(now);
    }

    private boolean isBayAffected(
            Appointment appointment,
            Map<UUID, ExtBayReplica> bayById,
            List<ExtBayReplica> locationBays,
            List<UUID> serviceRequestIds,
            Map<UUID, String> operationCodeByServiceId,
            @Nullable Integer gvwrClass) {
        UUID bayId = tryParseUuid(appointment.getResourceId());
        ExtBayReplica bay = bayId == null ? null : bayById.get(bayId);
        if (bay == null) {
            return true; // (a) missing
        }
        if (!bay.isActive()) {
            return true; // (b) OUT_OF_SERVICE or RETIRED, collapsed into active=false by the replica
        }
        Set<String> operationCodes = serviceRequestIds.stream()
                .map(operationCodeByServiceId::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // F6/#2280: a serviceRequestId absent from operationCodeByServiceId is one loadOperationCodes
        // could not resolve a code for (blank/null on the replica, or no matching catalog row) — still
        // general work, and must not silently vanish from the eligibility check as though unbooked.
        boolean hasUnresolvedOperation =
                serviceRequestIds.stream().anyMatch(id -> !operationCodeByServiceId.containsKey(id));
        return bayEligibilityService
                .refusalFor(
                        bay,
                        locationBays,
                        new BayEligibilityService.BookedOperations(operationCodes, hasUnresolvedOperation),
                        gvwrClass)
                .isPresent(); // (c) no longer eligible
    }

    private boolean isMobileUnitAffected(Appointment appointment, Map<UUID, ExtMobileUnitReplica> mobileUnitById) {
        UUID unitId = tryParseUuid(appointment.getResourceId());
        ExtMobileUnitReplica unit = unitId == null ? null : mobileUnitById.get(unitId);
        if (unit == null) {
            return true; // (a) missing
        }
        // (d): no cheap per-unit eligibility check exists yet beyond status/missing
        // (DECISION-SHOPMGMT-023, mobile scheduling not yet built) — the same subset
        // AppointmentsServiceImpl#resolveAndValidateResourceType already applies to a mobile unit.
        return !unit.isActive();
    }

    private <T> Map<UUID, T> loadById(
            List<Appointment> candidates, Function<Iterable<UUID>, List<T>> findAllById, Function<T, UUID> idOf) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ids = candidates.stream()
                .map(appointment -> tryParseUuid(appointment.getResourceId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, T> byId = new LinkedHashMap<>();
        for (T entity : findAllById.apply(ids)) {
            byId.put(idOf.apply(entity), entity);
        }
        return byId;
    }

    /** Every bay candidate's own service-request ids, batch loaded in one query. */
    private Map<UUID, List<UUID>> loadServiceRequestIds(List<Appointment> bayCandidates) {
        if (bayCandidates.isEmpty()) {
            return Map.of();
        }
        Set<UUID> appointmentIds = bayCandidates.stream()
                .map(Appointment::getAppointmentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, List<UUID>> byAppointment = new LinkedHashMap<>();
        for (AppointmentServiceRequest serviceRequest :
                appointmentServiceRequestRepository.findByAppointment_AppointmentIdIn(appointmentIds)) {
            byAppointment
                    .computeIfAbsent(serviceRequest.getAppointment().getAppointmentId(), ignored -> new ArrayList<>())
                    .add(serviceRequest.getServiceEntityId());
        }
        return byAppointment;
    }

    /** The normalized operation code named by every catalog service among every bay candidate's own ids, once. */
    private Map<UUID, String> loadOperationCodes(Map<UUID, List<UUID>> serviceRequestIdsByAppointment) {
        Set<UUID> allServiceIds = serviceRequestIdsByAppointment.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (allServiceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> operationCodeByServiceId = new LinkedHashMap<>();
        for (ExtCatalogServiceReplica service : catalogServiceRepository.findAllByServiceIdIn(allServiceIds)) {
            String normalized = SkillRequirementResolver.normalize(service.getOperationCode());
            if (!normalized.isEmpty()) {
                operationCodeByServiceId.put(service.getServiceId(), normalized);
            }
        }
        return operationCodeByServiceId;
    }

    /** Every bay candidate's vehicle GVWR class, batch loaded in one query. */
    private Map<UUID, Integer> loadGvwrClasses(List<Appointment> bayCandidates) {
        if (bayCandidates.isEmpty()) {
            return Map.of();
        }
        Set<UUID> vehicleIds = bayCandidates.stream()
                .map(Appointment::getCrmVehicleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> gvwrClassByVehicle = new LinkedHashMap<>();
        for (ExtVehicleReplica vehicle : vehicleReplicaRepository.findAllById(vehicleIds)) {
            gvwrClassByVehicle.put(vehicle.getVehicleId(), vehicle.getGvwrClass());
        }
        return gvwrClassByVehicle;
    }

    private static @Nullable UUID tryParseUuid(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
