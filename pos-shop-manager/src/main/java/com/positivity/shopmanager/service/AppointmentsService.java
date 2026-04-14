package com.positivity.shopmanager.service;

import com.positivity.shopmanager.internal.dto.AppointmentCreateModel;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.dto.AppointmentResponse;
import com.positivity.shopmanager.internal.dto.CancelAppointmentRequest;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.internal.dto.ScheduleViewRequest;
import com.positivity.shopmanager.internal.dto.ScheduleViewResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface AppointmentsService {

    /**
     * Creates an appointment from an Estimate or Work Order.
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
    AppointmentResponse createAppointment(
            @NonNull AppointmentCreateRequest request, String idempotencyKey, UUID correlationId);

    AppointmentResponse rescheduleAppointment(
            @NonNull UUID appointmentId, @NonNull RescheduleAppointmentRequest request);

    AppointmentResponse cancelAppointment(@NonNull UUID appointmentId, @NonNull CancelAppointmentRequest request);

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
    AppointmentCreateModel loadCreateModel(String sourceType, String sourceId, UUID facilityId, UUID correlationId);

    /**
     * Retrieves an appointment by ID.
     * Per DECISION-SHOPMGMT-012: enforces facility scoping in response (no
     * cross-facility leaks).
     *
     * @param appointmentId The appointment identifier
     * @param correlationId Optional request correlation ID
     * @return AppointmentResponse with appointment details
     */
    AppointmentResponse getById(String appointmentId, UUID correlationId);

    @NonNull
    ScheduleViewResponse getScheduleView(@NonNull ScheduleViewRequest request, UUID correlationId);
}
