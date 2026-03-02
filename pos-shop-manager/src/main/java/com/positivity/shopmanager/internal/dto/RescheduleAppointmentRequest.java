package com.positivity.shopmanager.internal.dto;

import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lombok.Data;

/**
 * Request payload for rescheduling an appointment.
 *
 * <p>
 * CAP-249 Story #11: reason is mandatory (enum); rescheduleReasonNotes is
 * required when {@code reason == OTHER} or when overriding a hard conflict.
 */
@Data
public class RescheduleAppointmentRequest {

    @NotNull
    private Instant newStartAt;

    @NotNull
    private Instant newEndAt;

    /** Mandatory reschedule reason. */
    @NotNull
    private RescheduleReasonCode reason;

    /**
     * Optional free-text notes; required when reason is OTHER or when overriding
     * a hard scheduling conflict.
     */
    @Size(max = 1000)
    private String rescheduleReasonNotes;

    /** Whether to notify the customer of this reschedule (defaults to true). */
    private boolean notifyCustomer = true;
}
