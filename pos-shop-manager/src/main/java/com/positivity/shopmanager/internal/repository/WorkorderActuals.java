package com.positivity.shopmanager.internal.repository;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The workorder actual-time block resolved for one appointment through {@link
 * WorkOrderAppointmentMapping} (issue #2021 F9) — the authoritative link the status sync already
 * resolves through, not {@code Appointment.workorderLinkRef} or {@code sourceType}/{@code
 * sourceId}, which are creation-time provenance only.
 *
 * <p>Sourced from {@link com.positivity.shopmanager.internal.entity.ExtWorkorderReplica}:
 * {@code workStartedAt}/{@code completedAt}/{@code expectedEndAt} are the owner's actual-time
 * block (ADR-0044 §6, #2021), never synthesised by this module.
 *
 * @param appointmentId the appointment the actuals were resolved for
 * @param workOrderId the linked workorder identifier, carried so a caller can name it (AC4)
 * @param workStartedAt actual start; null while the workorder has not started
 * @param completedAt actual finish; null while the workorder is still open
 * @param expectedEndAt the owner's projected finish; null in every fact published today (#2021 F5)
 */
public record WorkorderActuals(
        @NonNull UUID appointmentId,
        @NonNull UUID workOrderId,
        @Nullable Instant workStartedAt,
        @Nullable Instant completedAt,
        @Nullable Instant expectedEndAt) {}
