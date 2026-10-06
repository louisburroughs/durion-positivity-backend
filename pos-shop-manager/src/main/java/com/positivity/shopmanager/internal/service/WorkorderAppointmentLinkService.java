package com.positivity.shopmanager.internal.service;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Records which appointment a workorder belongs to, in {@code work_order_appointment_mapping}
 * (#2531).
 *
 * <p>The link is stated by the owner: {@code workorder.workorder.updated} carries the {@code
 * appointmentId} of the estimate the workorder was promoted from. Until that fact carried it and
 * this service wrote it, the mapping had readers (the status sync, the capacity read's
 * actual-versus-planned and carry-over) and no writer, so each of them answered as if no workorder
 * had ever come from an appointment.
 */
public interface WorkorderAppointmentLinkService {

    /**
     * Links the workorder to the appointment unless it is already linked.
     *
     * <p>Idempotent: a workorder emits a fact on every change and each one repeats the same
     * appointment. A workorder already mapped keeps its mapping, and an appointment this module does
     * not hold (not yet created here, or another tenant's) is skipped and logged rather than failing
     * the replica write the caller is in the middle of.
     *
     * <p>Runs in the caller's transaction, so the link commits with the replica row that reported it.
     *
     * @param workorderId the workorder the fact describes
     * @param appointmentId the appointment that fact names as the workorder's source
     * @return true when this call created the link; false when the workorder was already linked or
     *     the appointment is not held here
     */
    boolean link(@NonNull UUID workorderId, @NonNull UUID appointmentId);
}
