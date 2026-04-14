package com.positivity.shopmanager.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shopmanager.internal.client.CrmCustomerClient;
import com.positivity.shopmanager.internal.client.CrmVehicleClient;
import com.positivity.shopmanager.internal.client.HrAvailabilityClient;
import com.positivity.shopmanager.internal.dto.AppointmentCreateModel;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.dto.AppointmentResponse;
import com.positivity.shopmanager.internal.dto.CancelAppointmentRequest;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.internal.dto.ScheduleViewRequest;
import com.positivity.shopmanager.internal.dto.ScheduleViewResponse;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.AppointmentAudit;
import com.positivity.shopmanager.internal.entity.AppointmentServiceRequest;
import com.positivity.shopmanager.internal.entity.RescheduleHistory;
import com.positivity.shopmanager.internal.entity.Shop;
import com.positivity.shopmanager.internal.enums.AppointmentAction;
import com.positivity.shopmanager.internal.enums.AppointmentSourceType;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import com.positivity.shopmanager.internal.event.AppointmentCancelledEvent;
import com.positivity.shopmanager.internal.event.AppointmentCreatedEvent;
import com.positivity.shopmanager.internal.event.AppointmentCreatedFromEstimateEvent;
import com.positivity.shopmanager.internal.event.AppointmentCreatedFromWorkOrderEvent;
import com.positivity.shopmanager.internal.event.AppointmentRescheduledEvent;
import com.positivity.shopmanager.internal.exception.AppointmentNotFoundException;
import com.positivity.shopmanager.internal.exception.AppointmentStateException;
import com.positivity.shopmanager.internal.exception.AppointmentValidationException;
import com.positivity.shopmanager.internal.exception.CrmUnavailableException;
import com.positivity.shopmanager.internal.exception.LocationNotFoundException;
import com.positivity.shopmanager.internal.exception.ResourceNotFoundException;
import com.positivity.shopmanager.internal.exception.VehicleCustomerMismatchException;
import com.positivity.shopmanager.internal.repository.AppointmentAuditRepository;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AppointmentServiceRequestRepository;
import com.positivity.shopmanager.internal.repository.RescheduleHistoryRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import com.positivity.shopmanager.service.AppointmentLoadService;
import com.positivity.shopmanager.service.AppointmentsService;
import com.positivity.shopmanager.service.SourceEligibilityService;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

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
    private final AppointmentRepository appointmentRepository;
    private final AppointmentAuditRepository appointmentAuditRepository;
    private final RescheduleHistoryRepository rescheduleHistoryRepository;
    private final AppointmentServiceRequestRepository appointmentServiceRequestRepository;
    private final ObjectMapper objectMapper;
    private final AppointmentLoadService appointmentLoadService;
    private final CrmCustomerClient crmCustomerClient;
    private final CrmVehicleClient crmVehicleClient;
    private final HrAvailabilityClient hrAvailabilityClient;
    private final ApplicationEventPublisher eventPublisher;
    private final ShopRepository shopRepository;
    private final SourceEligibilityService sourceEligibilityService;
    private final Clock clock;

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
    public AppointmentResponse createAppointment(
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
        if (request.getServiceRequestIds() == null
                || request.getServiceRequestIds().isEmpty()) {
            throw new AppointmentValidationException("serviceRequestIds must contain at least one entry");
        }
        validateCrmIdentifiers(request.getCrmCustomerId(), request.getCrmVehicleId());

        if (normalizedIdempotencyKey != null) {
            Appointment existing = appointmentRepository
                    .findByIdempotencyKey(normalizedIdempotencyKey)
                    .orElse(null);
            if (existing != null) {
                ensureIdempotentRequestMatches(existing, request);
                return toResponse(existing);
            }
        }

        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        Map<String, Object> customerSnapshot;
        Map<String, Object> vehicleSnapshot;
        try {
            customerSnapshot = crmCustomerClient.getCustomerById(request.getCrmCustomerId());
            vehicleSnapshot = crmVehicleClient.getVehicleById(request.getCrmVehicleId());
        } catch (RestClientException exception) {
            throw new CrmUnavailableException("CRM service is unavailable", exception);
        }
        validateCrmRelationship(request.getCrmCustomerId(), request.getCrmVehicleId(), vehicleSnapshot);

        // Source eligibility validation (CAP-249 Story #12)
        if (request.getSourceType() != null) {
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
            if (request.getSourceType() == AppointmentSourceType.ESTIMATE) {
                sourceEligibilityService.validateEstimateEligibility(request.getSourceId(), facilityId);
            } else if (request.getSourceType() == AppointmentSourceType.WORK_ORDER) {
                sourceEligibilityService.validateWorkOrderEligibility(request.getSourceId(), facilityId);
            }
        }

        List<Appointment> conflicts = appointmentRepository
                .findByLocationIdAndStartAtLessThanAndEndAtGreaterThan(
                        request.getLocationId(), request.getEndAt(), request.getStartAt())
                .stream()
                .filter(existing -> Objects.equals(existing.getResourceId(), request.getResourceId()))
                .filter(existing -> existing.getStatus() == AppointmentStatus.SCHEDULED)
                .filter(existing -> !Objects.equals(existing.getCrmCustomerId(), request.getCrmCustomerId())
                        || !Objects.equals(existing.getCrmVehicleId(), request.getCrmVehicleId())
                        || !Objects.equals(existing.getStartAt(), request.getStartAt())
                        || !Objects.equals(existing.getEndAt(), request.getEndAt()))
                .toList();

        if (!conflicts.isEmpty()) {
            throw new AppointmentValidationException(
                    "Requested slot is already booked. " + conflicts.size() + " conflicting appointment(s) found.");
        }

        Appointment appointment = Appointment.builder()
                .status(AppointmentStatus.SCHEDULED)
                .locationId(request.getLocationId())
                .resourceId(request.getResourceId())
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

        Appointment saved = appointmentRepository.save(appointment);
        saveServiceRequests(saved, request.getServiceRequestIds());
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

        return toResponse(saved);
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

        Appointment appointment = appointmentRepository
                .findById(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(appointmentId));

        if (appointment.getStatus() != AppointmentStatus.SCHEDULED
                && appointment.getStatus() != AppointmentStatus.CHECKED_IN
                && appointment.getStatus() != AppointmentStatus.WAITING_FOR_PARTS) {
            throw new AppointmentStateException(
                    "Appointment must be SCHEDULED, CHECKED_IN, or WAITING_FOR_PARTS to reschedule, current status: "
                            + appointment.getStatus());
        }

        // TODO CAP-249 follow-up: enforce max 2 reschedules using
        // rescheduleHistoryRepository.countByAppointmentId(appointmentId)

        if (request.getReason() == null) {
            throw new AppointmentValidationException("reason is required for rescheduling");
        }

        if (RescheduleReasonCode.OTHER == request.getReason()
                && (request.getRescheduleReasonNotes() == null
                        || request.getRescheduleReasonNotes().isBlank())) {
            throw new AppointmentValidationException("rescheduleReasonNotes is required when reason is OTHER");
        }

        Instant previousStartAt = appointment.getStartAt();
        Instant previousEndAt = appointment.getEndAt();

        appointment.setStartAt(request.getNewStartAt());
        appointment.setEndAt(request.getNewEndAt());
        Appointment saved = appointmentRepository.save(appointment);

        String actorId = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        Instant rescheduledAt = Instant.now(clock);

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
                .conflictOverridden(false)
                .notifyCustomer(request.isNotifyCustomer())
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
                    null // assignmentStatus: resolved via assignment lookup in future story
                    ));
        }

        return toResponse(saved);
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
        ZoneId zoneId = resolveZoneId(request.getLocationId());
        LocalDate targetDate = request.getDate();

        TimeWindow dayWindow = resolveDayWindow(targetDate, zoneId, request.getRange());

        List<Appointment> locationAppointments =
                appointmentRepository.findByLocationIdAndStartAtLessThanAndEndAtGreaterThan(
                        request.getLocationId(), dayWindow.dayEndAt(), dayWindow.dayStartAt());

        if (locationAppointments.isEmpty() && !locationLooksKnown(request.getLocationId())) {
            throw new LocationNotFoundException(request.getLocationId());
        }

        Map<ResourceLaneKey, List<ScheduleViewResponse.ScheduleEventView>> eventsByLane = new HashMap<>();
        for (Appointment appointment : locationAppointments) {
            ResourceLaneKey laneKey = new ResourceLaneKey(
                    defaultResourceId(appointment.getResourceId()), defaultResourceType(appointment.getResourceType()));
            eventsByLane.computeIfAbsent(laneKey, key -> new ArrayList<>()).add(toScheduleEvent(appointment));
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

        Map<ResourceLaneKey, String> resourceNames =
                resolveResourceNames(request.getLocationId(), filteredByType.keySet());
        List<ScheduleViewResponse.ScheduleResourceView> resources = filteredByType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> toResourceView(entry.getKey(), entry.getValue(), resourceNames.get(entry.getKey())))
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
            try {
                hrAvailabilityClient.getAvailabilityOverlay(
                        request.getLocationId().toString(), targetDate);
                response.setAvailabilityOverlayStatus("AVAILABLE");
            } catch (Exception exception) {
                response.setAvailabilityOverlayStatus("UNAVAILABLE");
                warnings.add("HR_SYSTEM_UNAVAILABLE");
                log.warn("HR overlay unavailable for location {}: {}", request.getLocationId(), exception.getMessage());
            }
        } else {
            response.setAvailabilityOverlayStatus("NOT_REQUESTED");
        }
        response.setWarnings(warnings);
        return response;
    }

    private ZoneId resolveZoneId(UUID locationId) {
        String configuredTimezone = shopRepository
                .findById(locationId)
                .map(Shop::getTimezone)
                .filter(timezone -> timezone != null && !timezone.isBlank())
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

    private Map<ResourceLaneKey, String> resolveResourceNames(UUID locationId, Set<ResourceLaneKey> laneKeys) {
        Map<ResourceLaneKey, String> resourceNames = HashMap.newHashMap(laneKeys.size());
        if (laneKeys.isEmpty()) {
            return resourceNames;
        }

        Map<String, List<ResourceLaneKey>> technicianLanesByResourceId = indexResourceLanes(laneKeys, resourceNames);
        if (technicianLanesByResourceId.isEmpty()) {
            return resourceNames;
        }

        Shop shop = shopRepository.findById(locationId).orElse(null);
        if (!shopHasTechnicians(shop)) {
            return resourceNames;
        }

        applyTechnicianDisplayNames(shop, technicianLanesByResourceId, resourceNames);
        return resourceNames;
    }

    private Map<String, List<ResourceLaneKey>> indexResourceLanes(
            Set<ResourceLaneKey> laneKeys, Map<ResourceLaneKey, String> resourceNames) {
        Map<String, List<ResourceLaneKey>> technicianLanesByResourceId = new HashMap<>();
        for (ResourceLaneKey laneKey : laneKeys) {
            resourceNames.put(laneKey, defaultResourceDisplayName(laneKey));
            if ("TECHNICIAN".equalsIgnoreCase(laneKey.resourceType())) {
                technicianLanesByResourceId
                        .computeIfAbsent(laneKey.resourceId(), ignored -> new ArrayList<>())
                        .add(laneKey);
            }
        }
        return technicianLanesByResourceId;
    }

    private String defaultResourceDisplayName(ResourceLaneKey laneKey) {
        if ("UNASSIGNED".equals(laneKey.resourceId())) {
            return "Unassigned";
        }
        return laneKey.resourceId();
    }

    private boolean shopHasTechnicians(Shop shop) {
        return shop != null
                && shop.getTechnicians() != null
                && !shop.getTechnicians().isEmpty();
    }

    private void applyTechnicianDisplayNames(
            Shop shop,
            Map<String, List<ResourceLaneKey>> technicianLanesByResourceId,
            Map<ResourceLaneKey, String> resourceNames) {
        for (var technician : shop.getTechnicians()) {
            if (technician != null && technician.getId() != null) {
                List<ResourceLaneKey> technicianLanes =
                        technicianLanesByResourceId.get(technician.getId().toString());
                if (technicianLanes != null) {
                    String technicianName =
                            resolveTechnicianDisplayName(technician.getId().toString(), technician.getPersonId());
                    for (ResourceLaneKey laneKey : technicianLanes) {
                        resourceNames.put(laneKey, technicianName);
                    }
                }
            }
        }
    }

    private String resolveTechnicianDisplayName(String technicianId, Long personId) {
        if (personId == null) {
            return technicianId;
        }
        return "Technician " + personId;
    }

    private ScheduleViewResponse.ScheduleEventView toScheduleEvent(Appointment appointment) {
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
        return response;
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
