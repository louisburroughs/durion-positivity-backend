package com.positivity.shopmanager.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shopmanager.internal.dto.AppointmentCreateModel;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.dto.AppointmentCreation;
import com.positivity.shopmanager.internal.dto.AppointmentResponse;
import com.positivity.shopmanager.internal.dto.CancelAppointmentRequest;
import com.positivity.shopmanager.internal.dto.ConflictResponse;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.internal.dto.ScheduleViewRequest;
import com.positivity.shopmanager.internal.dto.ScheduleViewResponse;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.AppointmentAudit;
import com.positivity.shopmanager.internal.entity.AppointmentServiceRequest;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtMobileUnitReplica;
import com.positivity.shopmanager.internal.entity.ExtPersonReplica;
import com.positivity.shopmanager.internal.entity.RescheduleHistory;
import com.positivity.shopmanager.internal.entity.Shop;
import com.positivity.shopmanager.internal.enums.AppointmentAction;
import com.positivity.shopmanager.internal.enums.AppointmentSourceType;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import com.positivity.shopmanager.internal.enums.ResourceType;
import com.positivity.shopmanager.internal.event.AppointmentCancelledEvent;
import com.positivity.shopmanager.internal.event.AppointmentCreatedEvent;
import com.positivity.shopmanager.internal.event.AppointmentCreatedFromEstimateEvent;
import com.positivity.shopmanager.internal.event.AppointmentCreatedFromWorkOrderEvent;
import com.positivity.shopmanager.internal.event.AppointmentRescheduledEvent;
import com.positivity.shopmanager.internal.exception.AppointmentNotFoundException;
import com.positivity.shopmanager.internal.exception.AppointmentStateException;
import com.positivity.shopmanager.internal.exception.AppointmentValidationException;
import com.positivity.shopmanager.internal.exception.KeylessDuplicateReplayException;
import com.positivity.shopmanager.internal.exception.LocationNotFoundException;
import com.positivity.shopmanager.internal.exception.RescheduleApprovalReasonRequiredException;
import com.positivity.shopmanager.internal.exception.ResourceNotFoundException;
import com.positivity.shopmanager.internal.exception.SchedulingConflictException;
import com.positivity.shopmanager.internal.exception.ServicePositionEligibilityException;
import com.positivity.shopmanager.internal.exception.ServicePositionEligibilityException.Code;
import com.positivity.shopmanager.internal.exception.VehicleCustomerMismatchException;
import com.positivity.shopmanager.internal.repository.AppointmentAuditRepository;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.RescheduleHistoryRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import com.positivity.shopmanager.internal.repository.WorkOrderAppointmentMappingRepository;
import com.positivity.shopmanager.internal.repository.WorkorderActuals;
import com.positivity.shopmanager.internal.service.SchedulingConflictEvaluator.BookingAttempt;
import com.positivity.shopmanager.internal.service.SchedulingConflictEvaluator.DetectedConflict;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration service for appointment operations.
 * Coordinates shopmgmt domain logic: eligibility checks, conflict detection,
 * creation, retrieval.
 * Per DECISION-SHOPMGMT-001/002/008/011: implements appointment lifecycle and
 * conflict handling.
 * Per DECISION-SHOPMGMT-012: enforces facility scoping in all operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentsServiceImpl implements AppointmentsService {
    private static final String SYSTEM = "system";

    /** DECISION-SHOPMGMT-004: reschedules allowed before {@code appointments:reschedule:approve} is required. */
    private static final int MAX_FREE_RESCHEDULES = 2;

    private final AppointmentRepository appointmentRepository;
    private final AppointmentAuditRepository appointmentAuditRepository;
    private final RescheduleHistoryRepository rescheduleHistoryRepository;
    private final AppointmentServiceRequestRepository appointmentServiceRequestRepository;
    private final ObjectMapper objectMapper;
    private final AppointmentLoadService appointmentLoadService;
    private final CrmSnapshotService crmSnapshotService;
    private final StaffingScheduleService staffingScheduleService;
    private final ApplicationEventPublisher eventPublisher;
    private final ShopRepository shopRepository;
    private final SourceEligibilityService sourceEligibilityService;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;
    private final Clock clock;
    private final WorkOrderAppointmentMappingRepository workOrderAppointmentMappingRepository;
    private final SchedulingConflictEvaluator conflictEvaluator;
    private final SchedulingConflictRecorder conflictRecorder;
    private final BookingHorizonPolicy bookingHorizonPolicy;
    private final ExtBayReplicaRepository bayReplicaRepository;
    private final ExtMobileUnitReplicaRepository mobileUnitReplicaRepository;
    private final BayEligibilityService bayEligibilityService;
    private final SkillRequirementResolver skillRequirementResolver;
    private final AffectedAppointmentEvaluator affectedAppointmentEvaluator;
    private final RescheduleApprovalGuard rescheduleApprovalGuard;

    /**
     * Creates an appointment from an Estimate or Workorder.
     * Performs eligibility checks and conflict detection.
     *
     * Per DECISION-SHOPMGMT-014: supports idempotency via idempotencyKey.
     * Per DECISION-SHOPMGMT-011: correlationId propagates to downstream services
     * and error responses.
     *
     * @param request        The appointment create request
     * @param idempotencyKey Optional idempotency key (Idempotency-Key header)
     * @param correlationId  Optional request correlation ID (X-Correlation-Id
     *                       header)
     * @return AppointmentResponse with appointmentId and facility timezone
     * @throws com.positivity.shopmanager.internal.exception.SourceNotEligibleException  on
     *                                                                                   eligibility
     *                                                                                   failure
     *                                                                                   (422)
     * @throws com.positivity.shopmanager.internal.exception.SchedulingConflictException on
     *                                                                                   conflict
     *                                                                                   detection
     *                                                                                   (409)
     */
    @Override
    @Transactional
    public AppointmentCreation createAppointment(
            @NonNull AppointmentCreateRequest request, String idempotencyKey, UUID correlationId) {
        UUID normalizedCorrelationId = normalizeCorrelationId(correlationId);
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        log.info(
                "Create appointment requested. locationId={}, resourceId={}, correlationId={}, idempotencyKey={}",
                request.getLocationId(),
                request.getResourceId(),
                normalizedCorrelationId,
                normalizedIdempotencyKey);

        validateTimeRange(request.getStartAt(), request.getEndAt());
        validateServiceRequestIdsPresent(request.getServiceRequestIds());
        validateCrmIdentifiers(request.getCrmCustomerId(), request.getCrmVehicleId());

        Optional<AppointmentResponse> idempotentDuplicate = findIdempotentDuplicate(normalizedIdempotencyKey, request);
        if (idempotentDuplicate.isPresent()) {
            return AppointmentCreation.replayed(idempotentDuplicate.get());
        }

        // The booking horizon (DECISION-SHOPMGMT-019), checked once this is known to be a new
        // booking rather than a replay. A repeated Idempotency-Key replays the appointment it
        // already created (DECISION-SHOPMGMT-014), and a replay is not a write: lowering the
        // horizon must not turn an accepted booking's retry into a 422 for a row that already
        // exists. Still before the replica reads and before anything is persisted, so a genuinely
        // out-of-horizon booking is refused cheaply.
        bookingHorizonPolicy.verifyWithinHorizon(
                request.getStartAt(), resolveZoneId(request.getLocationId()), Instant.now(clock));

        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        // Local replica reads (ADR-0044 §6, #891): unknown ids raise the same not-found
        // exceptions the retired CRM HTTP clients mapped 404s to.
        Map<String, Object> customerSnapshot = crmSnapshotService.getCustomerById(request.getCrmCustomerId());
        Map<String, Object> vehicleSnapshot = crmSnapshotService.getVehicleById(request.getCrmVehicleId());
        validateCrmRelationship(request.getCrmCustomerId(), request.getCrmVehicleId(), vehicleSnapshot);

        // Source eligibility validation (CAP-249 Story #12)
        validateSourceEligibility(request);

        // Bay/mobile-unit eligibility (DECISION-SHOPMGMT-021/-003): the same rule the opening
        // search filters with, run here as a refusal. resourceType is inferred from the replicas
        // when the caller names a resourceId but not its kind — submit is authoritative, so a
        // caller cannot skip eligibility by simply omitting resourceType (DECISION-SHOPMGMT-011).
        ResourceType resolvedResourceType = resolveAndValidateResourceType(
                request.getLocationId(),
                request.getResourceId(),
                request.getResourceType(),
                request.getServiceRequestIds(),
                request.getCrmVehicleId());

        // A keyless exact resubmission replays the appointment it duplicates rather than booking a
        // second one (spec D17 item 3, DECISION-SHOPMGMT-014): not a new row, not a 409.
        Optional<Appointment> keylessDuplicate = conflictRecorder.findKeylessDuplicate(request);
        if (keylessDuplicate.isPresent()) {
            return AppointmentCreation.replayed(toResponse(keylessDuplicate.get()));
        }

        // The submit-time tier (DECISION-SHOPMGMT-002/-011, CAP-326): HARD refuses, SOFT warns and allows.
        BookingAttempt attempt = new BookingAttempt(
                request.getLocationId(),
                request.getResourceId(),
                request.getStartAt(),
                request.getEndAt(),
                null,
                request.getServiceRequestIds() == null ? List.of() : request.getServiceRequestIds(),
                request.getCrmVehicleId());
        List<DetectedConflict> conflicts = conflictEvaluator.evaluate(attempt);
        refuseIfHard(attempt, conflicts);

        Appointment saved = persistAppointment(
                request, actor, normalizedIdempotencyKey, customerSnapshot, vehicleSnapshot, resolvedResourceType);
        flushOrRefuseOverlap(attempt, request);
        conflictRecorder.recordAccepted(saved, conflicts);
        saveServiceRequests(saved, request.getServiceRequestIds());
        publishAppointmentCreatedEvents(saved);

        return AppointmentCreation.created(toResponse(saved));
    }

    /** HARD blocks: the refusal is recorded on its own connection, then the booking is refused. */
    private void refuseIfHard(BookingAttempt attempt, List<DetectedConflict> conflicts) {
        if (conflicts.stream().noneMatch(DetectedConflict::isHard)) {
            return;
        }
        conflictRecorder.recordRefused(attempt, conflicts);
        throw new SchedulingConflictException(conflictEnvelope(conflicts));
    }

    /**
     * Sends the pending INSERT or UPDATE to PostgreSQL so {@code appointment_resource_no_overlap}
     * (V8) answers now, inside this method, rather than at commit. A refusal aborts this
     * transaction, so everything after it runs on the recorder's fresh connection: first the
     * re-query that tells an exact keyless double-submit (replay) from a real double-booking, then
     * the BAY_DOUBLE_BOOKED record. Anything that is not the overlap constraint is rethrown as is.
     */
    private void flushOrRefuseOverlap(BookingAttempt attempt, @Nullable AppointmentCreateRequest request) {
        try {
            appointmentRepository.flush();
        } catch (DataIntegrityViolationException violation) {
            if (!ResourceOverlapViolation.matches(violation)) {
                throw violation;
            }
            if (request != null) {
                Optional<Appointment> raced = conflictRecorder.findKeylessDuplicate(request);
                if (raced.isPresent()) {
                    throw new KeylessDuplicateReplayException(raced.get().getAppointmentId());
                }
            }
            DetectedConflict overlap = conflictRecorder.recordRefusedOverlap(attempt);
            throw new SchedulingConflictException(conflictEnvelope(List.of(overlap)));
        }
    }

    /** DECISION-SHOPMGMT-002's envelope: every conflict that fired, HARD and SOFT, with the rule code verbatim. */
    private ConflictResponse conflictEnvelope(List<DetectedConflict> conflicts) {
        long hard = conflicts.stream().filter(DetectedConflict::isHard).count();
        List<ConflictResponse.Conflict> views = conflicts.stream()
                .map(conflict -> new ConflictResponse.Conflict(
                        conflict.severity().name(),
                        conflict.code(),
                        conflict.detail(),
                        conflict.severity().isOverridable(),
                        conflict.resourceId()))
                .toList();
        return new ConflictResponse(
                "SCHEDULING_CONFLICT",
                hard + " HARD scheduling conflict(s) block this booking",
                null,
                Instant.now(clock),
                views);
    }

    private void validateServiceRequestIdsPresent(List<UUID> serviceRequestIds) {
        if (serviceRequestIds == null || serviceRequestIds.isEmpty()) {
            throw new AppointmentValidationException("serviceRequestIds must contain at least one entry");
        }
    }

    /**
     * Resolves a repeated {@code Idempotency-Key} to its previously created
     * appointment.
     * Empty when no key was supplied or the key has not been seen before, in
     * which case the caller proceeds with a normal create.
     *
     * @throws AppointmentValidationException if the key was seen before but the
     *                                         request no longer matches what was
     *                                         originally submitted under it
     */
    private Optional<AppointmentResponse> findIdempotentDuplicate(
            String normalizedIdempotencyKey, @NonNull AppointmentCreateRequest request) {
        if (normalizedIdempotencyKey == null) {
            return Optional.empty();
        }
        return appointmentRepository
                .findByIdempotencyKey(normalizedIdempotencyKey)
                .map(existing -> {
                    ensureIdempotentRequestMatches(existing, request);
                    return toResponse(existing);
                });
    }

    /**
     * Validates sourceId/eligibility for a request that names an origin
     * (ESTIMATE/WORK_ORDER). No-op when {@code sourceType} is absent.
     *
     * <p>
     * {@link AppointmentSourceType} has exactly two constants, so once
     * {@code sourceType} is non-null and not ESTIMATE it is necessarily
     * WORK_ORDER; there is no reachable "neither" case to validate.
     */
    private void validateSourceEligibility(@NonNull AppointmentCreateRequest request) {
        if (request.getSourceType() == null) {
            return;
        }
        if (request.getSourceId() == null || request.getSourceId().isBlank()) {
            throw new AppointmentValidationException("sourceId is required when sourceType is provided");
        }
        String facilityId = request.getLocationId().toString();
        // Guard against duplicate appointment from same source entity
        String existingAppointmentId = sourceEligibilityService.getExistingAppointmentId(
                request.getSourceType().name(), request.getSourceId(), facilityId);
        if (existingAppointmentId != null) {
            throw new AppointmentValidationException(
                    "An appointment already exists for this source entity. appointmentId=" + existingAppointmentId);
        }
        switch (request.getSourceType()) {
            case ESTIMATE -> sourceEligibilityService.validateEstimateEligibility(request.getSourceId(), facilityId);
            case WORK_ORDER -> sourceEligibilityService.validateWorkOrderEligibility(request.getSourceId(), facilityId);
            // A source type with no eligibility rule must fail here, not book unvalidated: a
            // future enum constant that reaches this default was never wired into eligibility.
            default ->
                throw new AppointmentValidationException(
                        "No eligibility validation defined for sourceType " + request.getSourceType());
        }
    }

    private Appointment persistAppointment(
            @NonNull AppointmentCreateRequest request,
            String actor,
            String normalizedIdempotencyKey,
            Map<String, Object> customerSnapshot,
            Map<String, Object> vehicleSnapshot,
            @NonNull ResourceType resourceType) {
        Appointment appointment = Appointment.builder()
                .status(AppointmentStatus.SCHEDULED)
                .locationId(request.getLocationId())
                .resourceId(request.getResourceId())
                .resourceType(resourceType.name())
                .crmCustomerId(request.getCrmCustomerId())
                .crmVehicleId(request.getCrmVehicleId())
                .customerSnapshot(writeSnapshot(customerSnapshot))
                .vehicleSnapshot(writeSnapshot(vehicleSnapshot))
                .startAt(request.getStartAt())
                .endAt(request.getEndAt())
                .createdBy(actor)
                .workorderLinkRef(request.getWorkorderLinkRef())
                .idempotencyKey(normalizedIdempotencyKey)
                .sourceType(request.getSourceType())
                .sourceId(request.getSourceType() == null ? null : request.getSourceId())
                .build();

        return appointmentRepository.save(appointment);
    }

    /**
     * Parses a stored {@code appointment.resource_type}. {@code null} (never written) reads as "no
     * stated type" so {@link #resolveAndValidateResourceType} infers it from {@code resourceId}
     * when reschedule re-validates an older appointment; a stored value that is not one of the
     * three current constants — most notably {@code "TECHNICIAN"}, a distinct, already-existing
     * resource-type reading used elsewhere in this module for mechanic-busy tracking, never written
     * by this service — also reads as "no stated type" here, but callers that must keep such a
     * value's own semantics (reschedule) check for it before calling this, not after.
     */
    private static @Nullable ResourceType parseStoredResourceType(@Nullable String stored) {
        if (stored == null) {
            return null;
        }
        try {
            return ResourceType.valueOf(stored);
        } catch (IllegalArgumentException notARecognisedValue) {
            return null;
        }
    }

    /**
     * DECISION-SHOPMGMT-021/-003: resolves and validates the resource axis for a booking, against
     * the shared {@link BayEligibilityService} the opening search filters with. Submit is
     * authoritative (DECISION-SHOPMGMT-011), so a caller cannot skip eligibility by naming a real
     * {@code resourceId} while leaving {@code resourceType} out: the type is then inferred from
     * whichever replica actually holds that id, and validated exactly as if the caller had stated
     * it. Rules:
     *
     * <ul>
     *   <li>no {@code resourceId}: {@code resourceType} must be absent or {@code UNASSIGNED} — a
     *       stated {@code BAY}/{@code MOBILE_UNIT} needs a {@code resourceId} (400, field {@code
     *       resourceId})
     *   <li>{@code resourceId} present, {@code resourceType} explicitly {@code UNASSIGNED}: 400
     *       (field {@code resourceId}) — contradictory
     *   <li>{@code resourceId} present, {@code resourceType} omitted: inferred as {@code BAY} when
     *       an {@code ext_bay} row exists for it, else {@code MOBILE_UNIT} when an {@code
     *       ext_mobile_unit} row does; neither is 422 {@code SERVICE_POSITION_INVALID}
     *   <li>{@code resourceType} explicitly {@code BAY} or {@code MOBILE_UNIT}: validated as that
     *       kind — an id that resolves to the other kind, or to neither, is 422 {@code
     *       SERVICE_POSITION_INVALID}
     * </ul>
     *
     * <p>A validated {@code BAY} additionally runs specialty and duty-class; a {@code MOBILE_UNIT}
     * runs existence, location and active checks only, until mobile scheduling lands
     * (DECISION-SHOPMGMT-023).
     *
     * @return the resolved {@code resourceType} — for {@link #persistAppointment} to store, or for
     *     reschedule to have proven against the appointment's own resource
     */
    private ResourceType resolveAndValidateResourceType(
            @NonNull UUID locationId,
            @Nullable String resourceId,
            @Nullable ResourceType requestedResourceType,
            @Nullable List<UUID> serviceRequestIds,
            @Nullable UUID crmVehicleId) {
        boolean hasResourceId = resourceId != null && !resourceId.isBlank();

        if (!hasResourceId) {
            if (requestedResourceType != null && requestedResourceType != ResourceType.UNASSIGNED) {
                throw new AppointmentValidationException(
                        "resourceId is required when resourceType is " + requestedResourceType, "resourceId");
            }
            return ResourceType.UNASSIGNED;
        }
        if (requestedResourceType == ResourceType.UNASSIGNED) {
            throw new AppointmentValidationException(
                    "resourceId must not be set when resourceType is UNASSIGNED", "resourceId");
        }

        if (requestedResourceType == ResourceType.BAY) {
            ExtBayReplica bay = findBayOrInvalid(resourceId);
            validateBayEligibility(locationId, bay, resourceId, serviceRequestIds, crmVehicleId);
            return ResourceType.BAY;
        }
        if (requestedResourceType == ResourceType.MOBILE_UNIT) {
            ExtMobileUnitReplica unit = findMobileUnitOrInvalid(resourceId);
            validateMobileUnitEligibility(locationId, unit, resourceId);
            return ResourceType.MOBILE_UNIT;
        }

        // requestedResourceType == null: infer from whichever replica actually holds this id.
        UUID resourceUuid = tryParseUuid(resourceId);
        Optional<ExtBayReplica> bay =
                resourceUuid == null ? Optional.empty() : bayReplicaRepository.findById(resourceUuid);
        if (bay.isPresent()) {
            validateBayEligibility(locationId, bay.get(), resourceId, serviceRequestIds, crmVehicleId);
            return ResourceType.BAY;
        }
        Optional<ExtMobileUnitReplica> unit =
                resourceUuid == null ? Optional.empty() : mobileUnitReplicaRepository.findById(resourceUuid);
        if (unit.isPresent()) {
            validateMobileUnitEligibility(locationId, unit.get(), resourceId);
            return ResourceType.MOBILE_UNIT;
        }
        throw invalid("Resource", resourceId, "is not a known bay or mobile unit");
    }

    private ExtBayReplica findBayOrInvalid(String resourceId) {
        return bayReplicaRepository
                .findById(parseResourceIdOrInvalid("Bay", resourceId))
                .orElseThrow(() -> invalid("Bay", resourceId, "is unknown"));
    }

    private ExtMobileUnitReplica findMobileUnitOrInvalid(String resourceId) {
        return mobileUnitReplicaRepository
                .findById(parseResourceIdOrInvalid("Mobile unit", resourceId))
                .orElseThrow(() -> invalid("Mobile unit", resourceId, "is unknown"));
    }

    private void validateBayEligibility(
            UUID locationId,
            ExtBayReplica bay,
            String resourceId,
            @Nullable List<UUID> serviceRequestIds,
            @Nullable UUID crmVehicleId) {
        if (!locationId.equals(bay.getLocationId())) {
            throw invalid("Bay", resourceId, "belongs to another location");
        }
        if (!bay.isActive()) {
            throw inactive("Bay", resourceId);
        }

        Set<String> operationCodes = bayEligibilityService.operationCodesOf(serviceRequestIds);
        Integer gvwrClass = skillRequirementResolver.gvwrClassOf(crmVehicleId);
        List<ExtBayReplica> locationBays = bayReplicaRepository.findActiveByLocationOrdered(locationId);
        bayEligibilityService
                .refusalFor(bay, locationBays, operationCodes, gvwrClass)
                .ifPresent(refusal -> {
                    throw switch (refusal) {
                        case NOT_EQUIPPED ->
                            new ServicePositionEligibilityException(
                                    Code.SERVICE_POSITION_NOT_EQUIPPED,
                                    "Bay " + resourceId
                                            + " does not claim every specialty operation on this appointment, or"
                                            + " takes no general work");
                        case DUTY_CLASS_EXCEEDED ->
                            new ServicePositionEligibilityException(
                                    Code.SERVICE_POSITION_DUTY_CLASS_EXCEEDED,
                                    "Vehicle GVWR class " + gvwrClass + " exceeds bay " + resourceId
                                            + "'s maxDutyClass " + bay.getMaxDutyClass());
                    };
                });
    }

    private void validateMobileUnitEligibility(UUID locationId, ExtMobileUnitReplica unit, String resourceId) {
        if (!locationId.equals(unit.getBaseLocationId())) {
            throw invalid("Mobile unit", resourceId, "is based at another location");
        }
        if (!unit.isActive()) {
            throw inactive("Mobile unit", resourceId);
        }
    }

    private static @Nullable UUID tryParseUuid(String resourceId) {
        try {
            return UUID.fromString(resourceId);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static UUID parseResourceIdOrInvalid(String resourceLabel, String resourceId) {
        UUID parsed = tryParseUuid(resourceId);
        if (parsed == null) {
            throw invalid(resourceLabel, resourceId, "is not a recognised id");
        }
        return parsed;
    }

    private static ServicePositionEligibilityException invalid(
            String resourceLabel, @Nullable String resourceId, String detail) {
        return new ServicePositionEligibilityException(
                Code.SERVICE_POSITION_INVALID, resourceLabel + " " + resourceId + " " + detail);
    }

    private static ServicePositionEligibilityException inactive(String resourceLabel, @Nullable String resourceId) {
        return new ServicePositionEligibilityException(
                Code.SERVICE_POSITION_INACTIVE, resourceLabel + " " + resourceId + " is not ACTIVE");
    }

    private void publishAppointmentCreatedEvents(@NonNull Appointment saved) {
        eventPublisher.publishEvent(new AppointmentCreatedEvent(
                saved.getAppointmentId(),
                saved.getCrmCustomerId().toString(),
                saved.getCrmVehicleId().toString(),
                saved.getStartAt(),
                saved.getEndAt(),
                saved.getCreatedBy(),
                saved.getWorkorderLinkRef()));

        // Source-specific events (CAP-249 Story #12): notify WorkExec of the
        // appointment link.
        if (saved.getSourceType() == AppointmentSourceType.ESTIMATE) {
            eventPublisher.publishEvent(new AppointmentCreatedFromEstimateEvent(
                    saved.getAppointmentId(),
                    saved.getSourceId(),
                    saved.getCrmCustomerId().toString(),
                    saved.getCrmVehicleId().toString(),
                    saved.getStartAt(),
                    saved.getEndAt(),
                    saved.getCreatedBy(),
                    clock.instant()));
        } else if (saved.getSourceType() == AppointmentSourceType.WORK_ORDER) {
            eventPublisher.publishEvent(new AppointmentCreatedFromWorkOrderEvent(
                    saved.getAppointmentId(),
                    saved.getSourceId(),
                    saved.getCrmCustomerId().toString(),
                    saved.getCrmVehicleId().toString(),
                    saved.getStartAt(),
                    saved.getEndAt(),
                    saved.getCreatedBy(),
                    clock.instant()));
        }
    }

    /**
     * Reschedules an existing appointment to a new time slot.
     * Per DECISION-SHOPMGMT-011: correlationId propagates to downstream services
     * and
     * error responses.
     *
     * @param appointmentId The appointment identifier
     * @param request       The reschedule request containing newStartAt, newEndAt,
     *                      reason,
     *                      rescheduleReasonNotes, and notifyCustomer
     * @return AppointmentResponse with updated appointment details
     */
    @Override
    @Transactional
    public AppointmentResponse rescheduleAppointment(
            @NonNull UUID appointmentId, @NonNull RescheduleAppointmentRequest request) {
        if (request.getNewStartAt() == null || request.getNewEndAt() == null) {
            throw new AppointmentValidationException("newStartAt and newEndAt are required");
        }
        if (!request.getNewStartAt().isBefore(request.getNewEndAt())) {
            throw new AppointmentValidationException("newStartAt must be before newEndAt");
        }

        // DECISION-SHOPMGMT-004 (#2280 F3): findByIdForUpdate, not findById — a pessimistic write
        // lock held for the rest of this transaction serialises concurrent reschedules of the same
        // appointment, so enforceRescheduleAllowance's count and this method's own reschedule_history
        // insert are atomic together and cannot both observe "under the free allowance" at once.
        Appointment appointment = appointmentRepository
                .findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(appointmentId));

        if (appointment.getStatus() != AppointmentStatus.SCHEDULED
                && appointment.getStatus() != AppointmentStatus.CHECKED_IN
                && appointment.getStatus() != AppointmentStatus.WAITING_FOR_PARTS) {
            throw new AppointmentStateException(
                    "Appointment must be SCHEDULED, CHECKED_IN, or WAITING_FOR_PARTS to reschedule, current status: "
                            + appointment.getStatus());
        }

        if (request.getReason() == null) {
            throw new AppointmentValidationException("reason is required for rescheduling");
        }

        if (RescheduleReasonCode.OTHER == request.getReason()
                && (request.getRescheduleReasonNotes() == null
                        || request.getRescheduleReasonNotes().isBlank())) {
            throw new AppointmentValidationException("rescheduleReasonNotes is required when reason is OTHER");
        }

        // DECISION-SHOPMGMT-004/-022: the appointment's own affected state, judged BEFORE any field
        // of it changes — an exemption is decided on the condition that drove this reschedule, not
        // on where the appointment lands afterward. A reschedule is shop-caused, and so exempt from
        // the 2-free-reschedules allowance, when its reason is EQUIPMENT_ISSUE or the appointment
        // was already DECISION-SHOPMGMT-022 affected.
        boolean shopCaused = request.getReason() == RescheduleReasonCode.EQUIPMENT_ISSUE
                || affectedAppointmentEvaluator.evaluateOne(appointment);
        enforceRescheduleAllowance(appointmentId, shopCaused, request);

        // A reschedule is a write too, so the booking horizon binds it exactly as it binds a create
        // (DECISION-SHOPMGMT-019). Checked after the required fields and before the appointment is
        // touched: a refused reschedule leaves the previous window in place and records no history
        // row against the reschedule allowance (DECISION-SHOPMGMT-004).
        bookingHorizonPolicy.verifyWithinHorizon(
                request.getNewStartAt(), resolveZoneId(appointment.getLocationId()), Instant.now(clock));

        List<UUID> serviceRequestIds =
                appointmentServiceRequestRepository.findByAppointment_AppointmentId(appointmentId).stream()
                        .map(AppointmentServiceRequest::getServiceEntityId)
                        .toList();

        // DECISION-SHOPMGMT-022 rule 3: a reschedule may move the appointment onto a different
        // resource — most commonly off one that has gone out of service. When the caller names
        // either field, only the NEW resource is resolved and validated (DECISION-SHOPMGMT-021,
        // the same rule submit uses); the appointment's OLD resource is never re-validated in this
        // branch, so moving off an affected resource cannot be refused by the very condition being
        // fixed. Leaving both fields absent keeps today's behaviour: the appointment's own
        // (unchanged) resource is re-validated, below.
        boolean movingResource = request.getNewResourceId() != null || request.getNewResourceType() != null;
        String previousResourceId = appointment.getResourceId();
        String targetResourceId = previousResourceId;
        ResourceType targetResourceType = null;
        if (movingResource) {
            String newResourceIdText = request.getNewResourceId() == null
                    ? null
                    : request.getNewResourceId().toString();
            targetResourceType = resolveAndValidateResourceType(
                    appointment.getLocationId(),
                    newResourceIdText,
                    request.getNewResourceType(),
                    serviceRequestIds,
                    appointment.getCrmVehicleId());
            targetResourceId = newResourceIdText;
        } else if (!SchedulingConflictEvaluator.TECHNICIAN_RESOURCE_TYPE.equalsIgnoreCase(
                appointment.getResourceType())) {
            // DECISION-SHOPMGMT-021: reschedule re-runs the same eligibility rule submit does,
            // against the appointment's own (unchanged) resource — a bay that went out of service or
            // lost its specialty claim since booking is caught here rather than silently carried
            // forward. A stored resourceType of "TECHNICIAN" is a distinct, already-existing reading
            // this module uses for mechanic-busy tracking (never written by this service, and its
            // resourceId is a person id, not a bay/mobile-unit id) and is skipped entirely, not
            // passed through as UNASSIGNED — a real resourceId sits beside it, which the UNASSIGNED
            // path would otherwise refuse as contradictory. Any other missing or unrecognised stored
            // value — most commonly an appointment created before this column was written — is
            // inferred from resourceId exactly as a fresh submit would, rather than silently skipping
            // checks a real resourceId should still be subject to.
            targetResourceType = resolveAndValidateResourceType(
                    appointment.getLocationId(),
                    appointment.getResourceId(),
                    parseStoredResourceType(appointment.getResourceType()),
                    serviceRequestIds,
                    appointment.getCrmVehicleId());
        }

        Instant previousStartAt = appointment.getStartAt();
        Instant previousEndAt = appointment.getEndAt();

        // Same rules as creation (CAP-326): the appointment's own current slot does not count
        // against it, and the exclusion constraint checks the UPDATE exactly as it would an INSERT.
        // The attempt is evaluated against targetResourceId — the resource the appointment ends up
        // on, which is the appointment's own resource unless this reschedule is moving it.
        BookingAttempt attempt = new BookingAttempt(
                appointment.getLocationId(),
                targetResourceId,
                request.getNewStartAt(),
                request.getNewEndAt(),
                appointmentId,
                serviceRequestIds,
                appointment.getCrmVehicleId());
        List<DetectedConflict> conflicts = conflictEvaluator.evaluate(attempt);
        refuseIfHard(attempt, conflicts);

        appointment.setStartAt(request.getNewStartAt());
        appointment.setEndAt(request.getNewEndAt());
        if (movingResource) {
            appointment.setResourceId(targetResourceId);
            appointment.setResourceType(targetResourceType.name());
        }
        Appointment saved = appointmentRepository.save(appointment);
        flushOrRefuseOverlap(attempt, null);
        conflictRecorder.recordAccepted(saved, conflicts);

        String actorId = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        Instant rescheduledAt = Instant.now(clock);
        String newResourceIdForRecord = movingResource ? targetResourceId : null;

        AppointmentAudit audit = AppointmentAudit.builder()
                .appointment(appointment)
                .action(AppointmentAction.RESCHEDULED)
                .actorId(actorId)
                .previousStartAt(previousStartAt)
                .previousEndAt(previousEndAt)
                .newStartAt(request.getNewStartAt())
                .newEndAt(request.getNewEndAt())
                .build();
        appointmentAuditRepository.save(audit);

        rescheduleHistoryRepository.save(RescheduleHistory.builder()
                .appointment(appointment)
                .previousStartAt(previousStartAt)
                .previousEndAt(previousEndAt)
                .newStartAt(request.getNewStartAt())
                .newEndAt(request.getNewEndAt())
                .rescheduleReason(request.getReason())
                .rescheduleReasonNotes(request.getRescheduleReasonNotes())
                .rescheduledBy(actorId)
                .rescheduledAt(rescheduledAt)
                .notifyCustomer(request.isNotifyCustomer())
                .countsAgainstAllowance(!shopCaused)
                .previousResourceId(previousResourceId)
                .newResourceId(newResourceIdForRecord)
                .approvalReason(request.getApprovalReason())
                .createdAt(rescheduledAt)
                .build());

        // Event emission: only when workorderLinkRef is present (appointments without
        // a workorder link are scheduled independently and have no downstream consumer
        // expecting a reschedule signal at this time).
        if (saved.getWorkorderLinkRef() != null) {
            eventPublisher.publishEvent(new AppointmentRescheduledEvent(
                    saved.getAppointmentId(),
                    saved.getWorkorderLinkRef(),
                    previousStartAt,
                    previousEndAt,
                    request.getNewStartAt(),
                    request.getNewEndAt(),
                    request.getReason(),
                    actorId,
                    rescheduledAt,
                    null, // estimateId: not yet a typed UUID on Appointment entity
                    null, // workOrderId: not yet a typed UUID on Appointment entity
                    null, // assignmentStatus: resolved via assignment lookup in future story
                    previousResourceId,
                    newResourceIdForRecord));
        }

        return toResponse(saved);
    }

    /**
     * DECISION-SHOPMGMT-004: the 3rd and later reschedule of an appointment that is not
     * shop-caused needs {@code appointments:reschedule:approve} and a non-blank {@code
     * approvalReason}; the first two, and every shop-caused one, need neither. Checked before the
     * appointment is touched, like every other reschedule precondition — a refused reschedule
     * leaves the appointment and its reschedule count exactly as they were, since the rejected
     * attempt is never recorded to {@code reschedule_history} (the count this method itself reads
     * next time).
     *
     * @throws org.springframework.security.access.AccessDeniedException the caller lacks
     *     {@code appointments:reschedule:approve} (403)
     * @throws RescheduleApprovalReasonRequiredException {@code approvalReason} is missing or blank
     *     (422 {@code RESCHEDULE_APPROVAL_REASON_REQUIRED})
     */
    private void enforceRescheduleAllowance(
            @NonNull UUID appointmentId, boolean shopCaused, @NonNull RescheduleAppointmentRequest request) {
        if (shopCaused) {
            return;
        }
        long priorCountedReschedules =
                rescheduleHistoryRepository.countByAppointmentIdAndCountsAgainstAllowanceTrue(appointmentId);
        if (priorCountedReschedules < MAX_FREE_RESCHEDULES) {
            return;
        }
        rescheduleApprovalGuard.requireApprovalPermission();
        if (request.getApprovalReason() == null || request.getApprovalReason().isBlank()) {
            throw new RescheduleApprovalReasonRequiredException();
        }
    }

    /**
     * Cancels an existing appointment with a specified reason and optional notes.
     * Per DECISION-SHOPMGMT-011: correlationId propagates to downstream services
     * and error responses.
     *
     * @param appointmentId The appointment identifier
     * @param request       The cancel request with reason and optional notes
     * @return AppointmentResponse with updated appointment details
     */
    @Override
    @Transactional
    public AppointmentResponse cancelAppointment(
            @NonNull UUID appointmentId, @NonNull CancelAppointmentRequest request) {
        Appointment appointment = appointmentRepository
                .findById(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(appointmentId));

        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new AppointmentStateException("Appointment must be SCHEDULED to cancel");
        }

        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setCancellationReasonCode(request.getCancellationReason());
        appointment.setCancellationNotes(request.getNotes());
        Appointment saved = appointmentRepository.save(appointment);

        String actorId = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        AppointmentAudit audit = AppointmentAudit.builder()
                .appointment(appointment)
                .action(AppointmentAction.CANCELLED)
                .actorId(actorId)
                .cancellationReason(request.getCancellationReason().name())
                .build();
        appointmentAuditRepository.save(audit);

        if (saved.getWorkorderLinkRef() != null) {
            eventPublisher.publishEvent(new AppointmentCancelledEvent(
                    saved.getAppointmentId(),
                    saved.getWorkorderLinkRef(),
                    request.getCancellationReason().name(),
                    actorId));
        }

        return toResponse(saved);
    }

    /**
     * Loads the appointment creation form model for a source document.
     * Per DECISION-SHOPMGMT-012: enforces facility scoping.
     *
     * @param sourceType    ESTIMATE or WORKORDER
     * @param sourceId      The source identifier
     * @param facilityId    The facility identifier (required)
     * @param correlationId Optional request correlation ID
     * @return AppointmentCreateModel with facility context and operating hours
     */
    @Override
    public AppointmentCreateModel loadCreateModel(
            String sourceType, String sourceId, UUID facilityId, UUID correlationId) {
        UUID normalizedCorrelationId = normalizeCorrelationId(correlationId);
        log.info(
                "[AppointmentsService] loadCreateModel sourceType={}, sourceId={}, facilityId={}, correlationId={}",
                sourceType,
                sourceId,
                facilityId,
                normalizedCorrelationId);

        validateLoadCreateModelInputs(sourceType, sourceId, facilityId);
        if (!locationLooksKnown(facilityId)) {
            throw new LocationNotFoundException(facilityId);
        }

        String normalizedSourceType = normalizeSourceType(sourceType);
        String sourceStatus = validateAndResolveSourceStatus(normalizedSourceType, sourceId, facilityId);

        AppointmentCreateModel model = appointmentLoadService.loadCreateModel(
                normalizedSourceType, sourceId, facilityId, normalizedCorrelationId);
        if (model == null) {
            model = new AppointmentCreateModel();
        }

        enrichCreateModel(model, normalizedSourceType, sourceId, facilityId, sourceStatus);
        return model;
    }

    /**
     * Retrieves an appointment by ID.
     * Per DECISION-SHOPMGMT-012: enforces facility scoping in response (no
     * cross-facility leaks).
     *
     * @param appointmentId The appointment identifier
     * @param correlationId Optional request correlation ID
     * @return AppointmentResponse with appointment details
     */
    @Override
    @Transactional(readOnly = true)
    public AppointmentResponse getById(@NonNull String appointmentId, UUID correlationId) {
        UUID parsedId;
        try {
            parsedId = UUID.fromString(appointmentId);
        } catch (IllegalArgumentException e) {
            throw new AppointmentValidationException("Invalid appointment ID format: " + appointmentId);
        }
        Appointment appointment =
                appointmentRepository.findById(parsedId).orElseThrow(() -> new AppointmentNotFoundException(parsedId));
        log.debug("Loaded appointment. appointmentId={}, correlationId={}", parsedId, correlationId);
        return toResponse(appointment);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull ScheduleViewResponse getScheduleView(@NonNull ScheduleViewRequest request, UUID correlationId) {
        UUID normalizedCorrelationId = normalizeCorrelationId(correlationId);
        log.debug(
                "Building schedule view for location {} with correlationId={}",
                request.getLocationId(),
                normalizedCorrelationId);
        // One shop lookup, reused below by resolveZoneId and resolveResourceNames (#2023 F6) —
        // this used to be two separate shopRepository.findById calls for the same location.
        Shop shop = shopRepository.findById(request.getLocationId()).orElse(null);
        ZoneId zoneId = resolveZoneId(shop, request.getLocationId());
        LocalDate targetDate = request.getDate();

        TimeWindow dayWindow = resolveDayWindow(targetDate, zoneId, request.getRange());

        List<Appointment> locationAppointments =
                appointmentRepository.findByLocationIdAndStartAtLessThanAndEndAtGreaterThan(
                        request.getLocationId(), dayWindow.dayEndAt(), dayWindow.dayStartAt());

        if (locationAppointments.isEmpty() && !locationLooksKnown(request.getLocationId())) {
            throw new LocationNotFoundException(request.getLocationId());
        }

        // DECISION-SHOPMGMT-022, evaluated once for the whole board rather than once per event
        // (AffectedAppointmentEvaluator batches its own replica/service-request/vehicle reads).
        Map<UUID, Boolean> affectedByAppointmentId =
                affectedAppointmentEvaluator.evaluate(request.getLocationId(), locationAppointments);

        Map<ResourceLaneKey, List<ScheduleViewResponse.ScheduleEventView>> eventsByLane = new HashMap<>();
        for (Appointment appointment : locationAppointments) {
            ResourceLaneKey laneKey = new ResourceLaneKey(
                    defaultResourceId(appointment.getResourceId()), defaultResourceType(appointment.getResourceType()));
            eventsByLane
                    .computeIfAbsent(laneKey, key -> new ArrayList<>())
                    .add(toScheduleEvent(
                            appointment, affectedByAppointmentId.getOrDefault(appointment.getAppointmentId(), false)));
        }

        Map<ResourceLaneKey, List<ScheduleViewResponse.ScheduleEventView>> filteredByType =
                eventsByLane.entrySet().stream()
                        .filter(entry -> matchesResourceType(entry.getKey(), request.getResourceType()))
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (request.getResourceId() != null && !request.getResourceId().isBlank()) {
            filteredByType = filteredByType.entrySet().stream()
                    .filter(entry ->
                            request.getResourceId().equals(entry.getKey().resourceId()))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            if (filteredByType.isEmpty()) {
                throw new ResourceNotFoundException(request.getResourceId());
            }
        }

        Map<ResourceLaneKey, String> resourceNames = resolveResourceNames(filteredByType.keySet());
        List<ScheduleViewResponse.ScheduleResourceView> resources = filteredByType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> toResourceView(entry.getKey(), entry.getValue(), resourceNames.get(entry.getKey())))
                .map(resourceView -> applyAffectedFilter(resourceView, request.getAffected()))
                .filter(Objects::nonNull)
                .toList();

        ScheduleViewResponse response = new ScheduleViewResponse();
        response.setLocationId(request.getLocationId());
        response.setDate(targetDate);
        response.setViewGeneratedAt(Instant.now(clock));
        response.setDayStartAt(dayWindow.dayStartAt());
        response.setDayEndAt(dayWindow.dayEndAt());
        response.setResources(resources);

        List<String> warnings = new ArrayList<>();
        if (request.isIncludeAvailabilityOverlay()) {
            // Availability data is local since #877 (staffing-assignment replica); the overlay
            // probe reports whether the replica has staffing data for the location.
            boolean hasData = staffingScheduleService.hasAssignmentData(request.getLocationId());
            response.setAvailabilityOverlayStatus(hasData ? "AVAILABLE" : "UNAVAILABLE");
            if (!hasData) {
                warnings.add("HR_SYSTEM_UNAVAILABLE");
            }
        } else {
            response.setAvailabilityOverlayStatus("NOT_REQUESTED");
        }
        response.setWarnings(warnings);
        return response;
    }

    /** Looks up the shop itself; used only where the caller has no {@link Shop} in hand already. */
    private ZoneId resolveZoneId(UUID locationId) {
        return resolveZoneId(shopRepository.findById(locationId).orElse(null), locationId);
    }

    /**
     * Resolves the schedule window's timezone from an already-loaded {@link Shop} (#2023 F6): {@code
     * getScheduleView} loads the shop once and passes it here and to {@link
     * #resolveResourceNames(Set)} rather than querying it twice for the same location.
     */
    private ZoneId resolveZoneId(@Nullable Shop shop, UUID locationId) {
        String configuredTimezone = shop == null
                ? null
                : Optional.ofNullable(shop.getTimezone())
                        .filter(timezone -> !timezone.isBlank())
                        .orElse(null);
        if (configuredTimezone == null) {
            log.warn(
                    "Location {} has no configured timezone; defaulting to UTC. "
                            + "Schedule windows may be incorrect for non-UTC locations.",
                    locationId);
            return ZoneOffset.UTC;
        }
        return ZoneId.of(configuredTimezone);
    }

    private TimeWindow resolveDayWindow(LocalDate date, ZoneId zoneId, String range) {
        LocalDateTime startLocal;
        LocalDateTime endLocal;
        if ("FULL_DAY".equalsIgnoreCase(range)) {
            startLocal = date.atStartOfDay();
            endLocal = date.plusDays(1).atStartOfDay();
        } else {
            LocalTime openTime = LocalTime.of(6, 0);
            LocalTime closeTime = LocalTime.of(18, 0);
            startLocal = date.atTime(openTime);
            endLocal = date.atTime(closeTime);
        }
        return new TimeWindow(
                ZonedDateTime.of(startLocal, zoneId).toInstant(),
                ZonedDateTime.of(endLocal, zoneId).toInstant());
    }

    private boolean locationLooksKnown(UUID locationId) {
        return shopRepository.existsById(locationId);
    }

    private void validateLoadCreateModelInputs(String sourceType, String sourceId, UUID facilityId) {
        if (sourceType == null || sourceType.isBlank()) {
            throw new AppointmentValidationException("sourceType is required");
        }
        if (sourceId == null || sourceId.isBlank()) {
            throw new AppointmentValidationException("sourceId is required");
        }
        if (facilityId == null) {
            throw new AppointmentValidationException("facilityId is required");
        }
    }

    private String normalizeSourceType(String sourceType) {
        return sourceType.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private UUID normalizeCorrelationId(UUID correlationId) {
        if (correlationId != null) {
            return correlationId;
        }
        return UUIDv7Generator.generate();
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        String normalized = idempotencyKey.trim();
        if (normalized.isEmpty()) {
            throw new AppointmentValidationException("Idempotency-Key must not be blank");
        }
        if (normalized.length() > 128) {
            throw new AppointmentValidationException("Idempotency-Key must be 128 characters or fewer");
        }
        return normalized;
    }

    private void ensureIdempotentRequestMatches(Appointment existing, AppointmentCreateRequest request) {
        List<UUID> existingServiceRequestIds =
                normalizeServiceRequestIds(loadServiceRequestIds(existing.getAppointmentId()));
        List<UUID> requestedServiceRequestIds = normalizeServiceRequestIds(request.getServiceRequestIds());
        boolean matches = Objects.equals(existing.getLocationId(), request.getLocationId())
                && Objects.equals(existing.getResourceId(), request.getResourceId())
                && Objects.equals(existing.getCrmCustomerId(), request.getCrmCustomerId())
                && Objects.equals(existing.getCrmVehicleId(), request.getCrmVehicleId())
                && Objects.equals(existing.getStartAt(), request.getStartAt())
                && Objects.equals(existing.getEndAt(), request.getEndAt())
                && Objects.equals(existing.getWorkorderLinkRef(), request.getWorkorderLinkRef())
                && Objects.equals(existingServiceRequestIds, requestedServiceRequestIds);
        if (!matches) {
            throw new AppointmentValidationException("Idempotency-Key was already used with a different request");
        }
    }

    private List<UUID> normalizeServiceRequestIds(List<UUID> serviceRequestIds) {
        if (serviceRequestIds == null || serviceRequestIds.isEmpty()) {
            return List.of();
        }
        return serviceRequestIds.stream().sorted().distinct().toList();
    }

    private String validateAndResolveSourceStatus(String sourceType, String sourceId, UUID facilityId) {
        String facilityRef = facilityId.toString();
        return switch (sourceType) {
            case "ESTIMATE" -> {
                sourceEligibilityService.validateEstimateEligibility(sourceId, facilityRef);
                yield sourceEligibilityService.getEstimateStatus(sourceId, facilityRef);
            }
            case "WORKORDER" -> {
                sourceEligibilityService.validateWorkOrderEligibility(sourceId, facilityRef);
                yield sourceEligibilityService.getWorkOrderStatus(sourceId, facilityRef);
            }
            default -> throw new AppointmentValidationException("sourceType must be ESTIMATE or WORKORDER");
        };
    }

    private void enrichCreateModel(
            AppointmentCreateModel model, String sourceType, String sourceId, UUID facilityId, String sourceStatus) {
        model.setFacilityId(facilityId.toString());
        model.setSourceType(sourceType);
        model.setSourceId(sourceId);
        model.setSourceStatus(sourceStatus);
        model.setFacilityTimeZoneId(resolveCreateModelTimeZone(facilityId));

        if ("ESTIMATE".equals(sourceType)) {
            model.setEstimateId(sourceId);
            model.setWorkOrderId(null);
            return;
        }
        model.setWorkOrderId(sourceId);
        model.setEstimateId(null);
    }

    private String resolveCreateModelTimeZone(UUID facilityId) {
        String timeZone = appointmentLoadService.getFacilityTimeZoneId(facilityId);
        if (timeZone != null && !timeZone.isBlank()) {
            return timeZone;
        }
        return resolveZoneId(facilityId).getId();
    }

    private ScheduleViewResponse.ScheduleResourceView toResourceView(
            ResourceLaneKey laneKey, List<ScheduleViewResponse.ScheduleEventView> events, String resourceName) {
        List<ScheduleViewResponse.ScheduleEventView> sorted = events.stream()
                .sorted(Comparator.comparing(ScheduleViewResponse.ScheduleEventView::getStartTime)
                        .thenComparing(ScheduleViewResponse.ScheduleEventView::getEndTime)
                        .thenComparing(ScheduleViewResponse.ScheduleEventView::getEventId))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        detectConflicts(sorted);

        ScheduleViewResponse.ScheduleResourceView resourceView = new ScheduleViewResponse.ScheduleResourceView();
        resourceView.setResourceId(laneKey.resourceId());
        resourceView.setResourceType(laneKey.resourceType());
        resourceView.setResourceName(resourceName);
        resourceView.setEvents(sorted);
        return resourceView;
    }

    /**
     * DECISION-SHOPMGMT-022's {@code affected} filter: applied after {@link #toResourceView} so
     * conflict detection above still sees every event on the lane, never a pre-filtered subset. A
     * {@code null} filter (the default) passes every resource view through unchanged; {@code true}
     * or {@code false} narrows each lane's events to the matching ones, and a lane left with none is
     * dropped from the board entirely rather than shown empty.
     */
    private ScheduleViewResponse.@Nullable ScheduleResourceView applyAffectedFilter(
            ScheduleViewResponse.ScheduleResourceView resourceView, @Nullable Boolean affected) {
        if (affected == null) {
            return resourceView;
        }
        List<ScheduleViewResponse.ScheduleEventView> matching = resourceView.getEvents().stream()
                .filter(event -> affected == event.isAffected())
                .toList();
        if (matching.isEmpty()) {
            return null;
        }
        resourceView.setEvents(matching);
        return resourceView;
    }

    private Map<ResourceLaneKey, String> resolveResourceNames(Set<ResourceLaneKey> laneKeys) {
        Map<ResourceLaneKey, String> resourceNames = HashMap.newHashMap(laneKeys.size());
        if (laneKeys.isEmpty()) {
            return resourceNames;
        }

        Map<String, List<ResourceLaneKey>> technicianLanesByResourceId = new HashMap<>();
        Map<String, List<ResourceLaneKey>> bayLanesByResourceId = new HashMap<>();
        Map<String, List<ResourceLaneKey>> mobileUnitLanesByResourceId = new HashMap<>();
        for (ResourceLaneKey laneKey : laneKeys) {
            resourceNames.put(laneKey, defaultResourceDisplayName(laneKey));
            if ("TECHNICIAN".equalsIgnoreCase(laneKey.resourceType())) {
                technicianLanesByResourceId
                        .computeIfAbsent(laneKey.resourceId(), ignored -> new ArrayList<>())
                        .add(laneKey);
            } else if (ResourceType.BAY.name().equalsIgnoreCase(laneKey.resourceType())) {
                bayLanesByResourceId
                        .computeIfAbsent(laneKey.resourceId(), ignored -> new ArrayList<>())
                        .add(laneKey);
            } else if (ResourceType.MOBILE_UNIT.name().equalsIgnoreCase(laneKey.resourceType())) {
                mobileUnitLanesByResourceId
                        .computeIfAbsent(laneKey.resourceId(), ignored -> new ArrayList<>())
                        .add(laneKey);
            }
        }

        if (!technicianLanesByResourceId.isEmpty()) {
            applyTechnicianDisplayNames(technicianLanesByResourceId, resourceNames);
        }
        if (!bayLanesByResourceId.isEmpty()) {
            applyBayDisplayNames(bayLanesByResourceId, resourceNames);
        }
        if (!mobileUnitLanesByResourceId.isEmpty()) {
            applyMobileUnitDisplayNames(mobileUnitLanesByResourceId, resourceNames);
        }
        return resourceNames;
    }

    /**
     * Bay display names come from {@code ext_bay.name} (#2270), looked up by {@link
     * ExtBayReplicaRepository#findAllById}, never the active-only {@code
     * findActiveByLocationOrdered} — a bay that has gone out of service or been retired is kept as
     * an {@code active=false} row, never deleted (DECISION-LOCATION-026), specifically so an
     * appointment that still names it (DECISION-SHOPMGMT-022's affected queue, most of all) can
     * still show a name rather than a bare id. Falls back to the raw id when the replica has not
     * arrived yet, or its name is blank.
     */
    private void applyBayDisplayNames(
            Map<String, List<ResourceLaneKey>> bayLanesByResourceId, Map<ResourceLaneKey, String> resourceNames) {
        Map<String, UUID> bayIdByResourceId = parseResourceIds(bayLanesByResourceId.keySet());
        if (bayIdByResourceId.isEmpty()) {
            return;
        }
        Map<UUID, ExtBayReplica> bayById = bayReplicaRepository.findAllById(bayIdByResourceId.values()).stream()
                .collect(Collectors.toMap(ExtBayReplica::getBayId, bay -> bay));
        bayIdByResourceId.forEach((resourceId, bayId) -> {
            ExtBayReplica bay = bayById.get(bayId);
            String name = bay == null || bay.getName() == null || bay.getName().isBlank() ? resourceId : bay.getName();
            for (ResourceLaneKey laneKey : bayLanesByResourceId.get(resourceId)) {
                resourceNames.put(laneKey, name);
            }
        });
    }

    /** Mobile-unit counterpart of {@link #applyBayDisplayNames}, from {@code ext_mobile_unit.name}. */
    private void applyMobileUnitDisplayNames(
            Map<String, List<ResourceLaneKey>> mobileUnitLanesByResourceId,
            Map<ResourceLaneKey, String> resourceNames) {
        Map<String, UUID> unitIdByResourceId = parseResourceIds(mobileUnitLanesByResourceId.keySet());
        if (unitIdByResourceId.isEmpty()) {
            return;
        }
        Map<UUID, ExtMobileUnitReplica> unitById =
                mobileUnitReplicaRepository.findAllById(unitIdByResourceId.values()).stream()
                        .collect(Collectors.toMap(ExtMobileUnitReplica::getMobileUnitId, unit -> unit));
        unitIdByResourceId.forEach((resourceId, unitId) -> {
            ExtMobileUnitReplica unit = unitById.get(unitId);
            String name =
                    unit == null || unit.getName() == null || unit.getName().isBlank() ? resourceId : unit.getName();
            for (ResourceLaneKey laneKey : mobileUnitLanesByResourceId.get(resourceId)) {
                resourceNames.put(laneKey, name);
            }
        });
    }

    /** Every {@code resourceId} in {@code resourceIds} that parses as a UUID, keyed by itself. */
    private static Map<String, UUID> parseResourceIds(Set<String> resourceIds) {
        Map<String, UUID> byResourceId = new LinkedHashMap<>();
        for (String resourceId : resourceIds) {
            UUID parsed = tryParseUuid(resourceId);
            if (parsed != null) {
                byResourceId.put(resourceId, parsed);
            }
        }
        return byResourceId;
    }

    private String defaultResourceDisplayName(ResourceLaneKey laneKey) {
        if ("UNASSIGNED".equals(laneKey.resourceId())) {
            return "Unassigned";
        }
        return laneKey.resourceId();
    }

    /**
     * Technician lanes are keyed by the person id (the one identity this module has for a
     * technician, CAP-328), so a lane's display name is the person's name from the people-contact
     * replica — one replica read for the whole view (#885), a placeholder while it catches up, and
     * the raw id for a lane key that is not a person id at all.
     */
    private void applyTechnicianDisplayNames(
            Map<String, List<ResourceLaneKey>> technicianLanesByResourceId,
            Map<ResourceLaneKey, String> resourceNames) {
        Map<String, UUID> personIdsByResourceId = new HashMap<>();
        for (String resourceId : technicianLanesByResourceId.keySet()) {
            UUID personId = parsePersonId(resourceId);
            if (personId != null) {
                personIdsByResourceId.put(resourceId, personId);
            }
        }
        if (personIdsByResourceId.isEmpty()) {
            return;
        }
        Map<UUID, ExtPersonReplica> replicasByPersonId =
                extPersonReplicaRepository.findAllById(personIdsByResourceId.values()).stream()
                        .collect(Collectors.toMap(ExtPersonReplica::getPersonId, r -> r));
        personIdsByResourceId.forEach((resourceId, personId) -> {
            String technicianName =
                    resolveTechnicianDisplayName(resourceId, personId, replicasByPersonId.get(personId));
            for (ResourceLaneKey laneKey : technicianLanesByResourceId.get(resourceId)) {
                resourceNames.put(laneKey, technicianName);
            }
        });
    }

    private static @Nullable UUID parsePersonId(String resourceId) {
        try {
            return UUID.fromString(resourceId);
        } catch (IllegalArgumentException notAPersonId) {
            return null;
        }
    }

    private String resolveTechnicianDisplayName(String technicianId, UUID personId, ExtPersonReplica replica) {
        if (personId == null) {
            return technicianId;
        }
        // #885: person names come from the local people-contact replica; fall back to a
        // placeholder while the replica catches up.
        if (replica == null) {
            return "Technician " + personId;
        }
        String name = ((replica.getFirstName() == null ? "" : replica.getFirstName() + " ")
                        + (replica.getLastName() == null ? "" : replica.getLastName()))
                .trim();
        return name.isBlank() ? "Technician " + personId : name;
    }

    private ScheduleViewResponse.ScheduleEventView toScheduleEvent(Appointment appointment, boolean affected) {
        ScheduleViewResponse.ScheduleEventView event = new ScheduleViewResponse.ScheduleEventView();
        event.setEventId("APT-" + appointment.getAppointmentId());
        event.setEventType("APPOINTMENT");
        event.setSubType(null);
        event.setStartTime(appointment.getStartAt());
        event.setEndTime(appointment.getEndAt());
        event.setTitle(resolveEventTitle(appointment));
        event.setHasConflict(false);
        event.setSeverity(null);
        event.setConflictDetails(null);
        event.setAffected(affected);
        return event;
    }

    private String resolveEventTitle(Appointment appointment) {
        try {
            if (appointment.getCustomerSnapshot() != null) {
                JsonNode node = objectMapper.readTree(appointment.getCustomerSnapshot());
                String lastName = node.path("lastName").asText("");
                String firstName = node.path("firstName").asText("");
                if (!lastName.isEmpty()) {
                    return lastName + (firstName.isEmpty() ? "" : ", " + firstName);
                }
            }
        } catch (Exception exception) {
            log.debug("Could not parse customer snapshot for title: {}", exception.getMessage());
        }
        return "Appointment";
    }

    private void detectConflicts(List<ScheduleViewResponse.ScheduleEventView> events) {
        Map<String, Set<String>> conflictsByEvent = collectConflictsByEvent(events);
        applyConflictMetadata(events, conflictsByEvent);
    }

    private Map<String, Set<String>> collectConflictsByEvent(List<ScheduleViewResponse.ScheduleEventView> events) {
        Map<String, Set<String>> conflictsByEvent = new HashMap<>();
        for (int i = 0; i < events.size(); i++) {
            ScheduleViewResponse.ScheduleEventView current = events.get(i);
            if (isConflictExempt(current)) {
                continue;
            }
            collectConflictsForCurrentEvent(events, i, current, conflictsByEvent);
        }
        return conflictsByEvent;
    }

    private void collectConflictsForCurrentEvent(
            List<ScheduleViewResponse.ScheduleEventView> events,
            int currentIndex,
            ScheduleViewResponse.ScheduleEventView current,
            Map<String, Set<String>> conflictsByEvent) {
        for (ScheduleViewResponse.ScheduleEventView candidate : events.subList(currentIndex + 1, events.size())) {
            if (shouldStopConflictScan(current, candidate)) {
                return;
            }
            if (isBlockingConflictCandidate(current, candidate)) {
                registerConflict(conflictsByEvent, current, candidate);
            }
        }
    }

    private boolean shouldStopConflictScan(
            ScheduleViewResponse.ScheduleEventView current, ScheduleViewResponse.ScheduleEventView candidate) {
        return !candidateStartsBeforeCurrentEnds(current, candidate);
    }

    private boolean isBlockingConflictCandidate(
            ScheduleViewResponse.ScheduleEventView current, ScheduleViewResponse.ScheduleEventView candidate) {
        return !isConflictExempt(candidate) && hasBlockingOverlap(current, candidate);
    }

    private boolean candidateStartsBeforeCurrentEnds(
            ScheduleViewResponse.ScheduleEventView current, ScheduleViewResponse.ScheduleEventView candidate) {
        return candidate.getStartTime().isBefore(current.getEndTime());
    }

    private void registerConflict(
            Map<String, Set<String>> conflictsByEvent,
            ScheduleViewResponse.ScheduleEventView left,
            ScheduleViewResponse.ScheduleEventView right) {
        conflictsByEvent
                .computeIfAbsent(left.getEventId(), ignored -> new HashSet<>())
                .add(right.getEventId());
        conflictsByEvent
                .computeIfAbsent(right.getEventId(), ignored -> new HashSet<>())
                .add(left.getEventId());
    }

    private void applyConflictMetadata(
            List<ScheduleViewResponse.ScheduleEventView> events, Map<String, Set<String>> conflictsByEvent) {
        for (ScheduleViewResponse.ScheduleEventView event : events) {
            Set<String> conflictIds = conflictsByEvent.getOrDefault(event.getEventId(), Set.of());
            if (conflictIds.isEmpty()) {
                event.setHasConflict(false);
                event.setSeverity(null);
                event.setConflictDetails(null);
                continue;
            }

            event.setHasConflict(true);
            event.setSeverity("BLOCKING");
            ScheduleViewResponse.ConflictDetails details = new ScheduleViewResponse.ConflictDetails();
            details.setConflictingEventIds(conflictIds.stream().sorted().toList());
            event.setConflictDetails(details);
        }
    }

    private boolean hasBlockingOverlap(
            ScheduleViewResponse.ScheduleEventView left, ScheduleViewResponse.ScheduleEventView right) {
        Instant overlapStart =
                left.getStartTime().isAfter(right.getStartTime()) ? left.getStartTime() : right.getStartTime();
        Instant overlapEnd = left.getEndTime().isBefore(right.getEndTime()) ? left.getEndTime() : right.getEndTime();
        if (!overlapStart.isBefore(overlapEnd)) {
            return false;
        }
        long overlapMinutes = ChronoUnit.MINUTES.between(overlapStart, overlapEnd);
        return overlapMinutes >= 1;
    }

    private boolean isConflictExempt(ScheduleViewResponse.ScheduleEventView event) {
        if (event.getSubType() == null) {
            return false;
        }
        return "NOTE_BLOCK".equals(event.getSubType())
                || "SOFT_HOLD".equals(event.getSubType())
                || "BUFFER".equals(event.getSubType());
    }

    private String defaultResourceId(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return "UNASSIGNED";
        }
        return resourceId;
    }

    private String defaultResourceType(String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            return "UNKNOWN";
        }
        return resourceType;
    }

    private boolean matchesResourceType(ResourceLaneKey laneKey, String requestedType) {
        if (requestedType == null || requestedType.isBlank()) {
            return true;
        }
        return Objects.equals(laneKey.resourceType(), requestedType);
    }

    private record ResourceLaneKey(String resourceId, String resourceType) implements Comparable<ResourceLaneKey> {
        @Override
        public int compareTo(ResourceLaneKey other) {
            int typeCompare = this.resourceType.compareTo(other.resourceType);
            if (typeCompare != 0) {
                return typeCompare;
            }
            return this.resourceId.compareTo(other.resourceId);
        }
    }

    private record TimeWindow(Instant dayStartAt, Instant dayEndAt) {}

    private void validateTimeRange(Instant startAt, Instant endAt) {
        if (startAt == null || endAt == null) {
            throw new AppointmentValidationException("startAt and endAt are required");
        }

        if (!startAt.isBefore(endAt)) {
            throw new AppointmentValidationException("startAt must be before endAt");
        }
    }

    private void validateCrmIdentifiers(UUID crmCustomerId, UUID crmVehicleId) {
        if (crmCustomerId == null) {
            throw new AppointmentValidationException("crmCustomerId is required");
        }

        if (crmVehicleId == null) {
            throw new AppointmentValidationException("crmVehicleId is required");
        }
    }

    private void validateCrmRelationship(UUID crmCustomerId, UUID crmVehicleId, Map<String, Object> vehicleSnapshot) {

        UUID ownerCustomerId = extractOwnerCustomerId(vehicleSnapshot);
        if (ownerCustomerId != null && !ownerCustomerId.equals(crmCustomerId)) {
            throw new VehicleCustomerMismatchException(crmVehicleId, crmCustomerId);
        }
    }

    private UUID extractOwnerCustomerId(Map<String, Object> vehicleSnapshot) {
        Object ownerCustomerId = vehicleSnapshot.get("ownerCustomerId");
        if (ownerCustomerId == null) {
            ownerCustomerId = vehicleSnapshot.get("customerId");
        }
        if (ownerCustomerId == null) {
            ownerCustomerId = vehicleSnapshot.get("crmCustomerId");
        }
        if (ownerCustomerId instanceof UUID uuid) {
            return uuid;
        }
        if (ownerCustomerId instanceof String ownerCustomerIdText && !ownerCustomerIdText.isBlank()) {
            return UUID.fromString(ownerCustomerIdText);
        }
        return null;
    }

    private String writeSnapshot(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new AppointmentValidationException("Failed to serialize appointment snapshot");
        }
    }

    private Map<String, Object> readSnapshot(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private AppointmentResponse toResponse(Appointment appointment) {
        AppointmentResponse response = new AppointmentResponse();
        response.setAppointmentId(appointment.getAppointmentId());
        response.setConflicts(conflictRecorder.viewsFor(appointment.getAppointmentId()));
        response.setStatus(appointment.getStatus().name());
        response.setLocationId(appointment.getLocationId());
        response.setResourceId(appointment.getResourceId());
        response.setCrmCustomerId(appointment.getCrmCustomerId());
        response.setCrmVehicleId(appointment.getCrmVehicleId());
        response.setStartAt(appointment.getStartAt());
        response.setEndAt(appointment.getEndAt());
        response.setCreatedAt(appointment.getCreatedAt());
        response.setCancellationReason(
                appointment.getCancellationReasonCode() != null
                        ? appointment.getCancellationReasonCode().name()
                        : null);
        response.setCancellationNotes(appointment.getCancellationNotes());
        response.setServiceRequestIds(loadServiceRequestIds(appointment.getAppointmentId()));
        response.setCustomerSnapshot(readSnapshot(appointment.getCustomerSnapshot()));
        response.setVehicleSnapshot(readSnapshot(appointment.getVehicleSnapshot()));

        WorkorderActuals actuals = resolveWorkorderActuals(appointment.getAppointmentId());
        response.setActualStartAt(actuals == null ? null : actuals.workStartedAt());
        response.setActualEndAt(actuals == null ? null : actuals.completedAt());
        response.setExpectedEndAt(actuals == null ? null : actuals.expectedEndAt());
        // DECISION-SHOPMGMT-022: derived fresh on every read, never stored (see toResponse's
        // javadoc note above resolveWorkorderActuals — this is likewise never called in a loop).
        response.setAffected(affectedAppointmentEvaluator.evaluateOne(appointment));
        return response;
    }

    /**
     * Resolves the actual-time block for one appointment through {@link
     * com.positivity.shopmanager.internal.entity.WorkOrderAppointmentMapping} (#2021 F9) — the
     * authoritative appointment↔workorder link, not {@code workorderLinkRef} or
     * {@code sourceType}/{@code sourceId}, which are creation-time provenance only. Null when the
     * appointment has no linked workorder, or the workorder's replica has not landed yet.
     *
     * <p>{@code toResponse} is never called in a loop today (create, the idempotent-duplicate lookup,
     * reschedule, cancel, and getById each resolve exactly one appointment), so one extra query here
     * costs a single round trip per call, not an N+1 across a list.
     *
     * <p>More than one mapping row can resolve for this appointment (#2023 S1 — appointment ->
     * mapping is one-to-many; only {@code workOrderId} is unique in the baseline schema), so the
     * duplicates are collapsed through {@link WorkorderActuals#mostCurrent} — the same rule {@link
     * com.positivity.shopmanager.internal.service.ScheduleCapacityServiceImpl} applies to its own
     * batch resolution (#2023 F3) — rather than taking whichever row the database happens to return
     * first, which is nondeterministic and can surface a stale workorder's actuals.
     *
     * <p>The mapping rows come from an inner join against the workorder replica, so while a
     * reopened work order's replica is still in flight {@code mostCurrent} resolves the newest
     * <em>replicated</em> mapping and this response carries the superseded run's actual times
     * (#2089, option 1 — recorded on {@link WorkorderActuals#mostCurrent}). That matches what
     * {@code ScheduleCapacityServiceImpl} reports for the same appointment, deliberately: both
     * reduce the same query's rows through the same rule, so a caller comparing an appointment's
     * detail with the capacity board never sees the two disagree.
     */
    private @Nullable WorkorderActuals resolveWorkorderActuals(@NonNull UUID appointmentId) {
        List<WorkorderActuals> actuals =
                workOrderAppointmentMappingRepository.findActualsByAppointmentIds(List.of(appointmentId));
        return actuals.stream().reduce(WorkorderActuals::mostCurrent).orElse(null);
    }

    private void saveServiceRequests(Appointment appointment, List<UUID> serviceRequestIds) {
        if (serviceRequestIds == null || serviceRequestIds.isEmpty()) {
            return;
        }

        List<AppointmentServiceRequest> entries = serviceRequestIds.stream()
                .map(serviceEntityId -> AppointmentServiceRequest.builder()
                        .appointment(appointment)
                        .serviceEntityId(serviceEntityId)
                        .build())
                .toList();
        appointmentServiceRequestRepository.saveAll(entries);
    }

    private List<UUID> loadServiceRequestIds(UUID appointmentId) {
        return appointmentServiceRequestRepository.findByAppointment_AppointmentId(appointmentId).stream()
                .map(AppointmentServiceRequest::getServiceEntityId)
                .toList();
    }
}
