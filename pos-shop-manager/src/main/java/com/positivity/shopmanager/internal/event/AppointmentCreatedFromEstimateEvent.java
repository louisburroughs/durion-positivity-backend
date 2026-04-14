package com.positivity.shopmanager.internal.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after an appointment is successfully created from an Estimate.
 * <p>
 * CAP-249 Story #12: consumed by WorkExec to transition the estimate to
 * SCHEDULED
 * and to trigger downstream notification flows.
 *
 * @param appointmentId The newly created appointment identifier.
 * @param estimateId    The source estimate identifier.
 * @param crmCustomerId CRM customer identifier for downstream routing.
 * @param crmVehicleId  CRM vehicle identifier for downstream routing.
 * @param startAt       Scheduled start time.
 * @param endAt         Scheduled end time.
 * @param actorId       Username or system identifier that created the
 *                      appointment.
 * @param createdAt     Wall-clock time the appointment was persisted.
 */
public record AppointmentCreatedFromEstimateEvent(
        UUID appointmentId,
        String estimateId,
        String crmCustomerId,
        String crmVehicleId,
        Instant startAt,
        Instant endAt,
        String actorId,
        Instant createdAt) {}
