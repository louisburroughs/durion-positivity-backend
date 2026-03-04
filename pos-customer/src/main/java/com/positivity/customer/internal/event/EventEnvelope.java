package com.positivity.customer.internal.event;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Inbound event envelope carrying a workorder-originated event to the CRM system.
 *
 * <p>The {@code eventType} field discriminates which event-specific payload
 * is contained in the {@code payload} map.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventEnvelope {

    /** Unique identifier for this event instance (used for idempotency checks). */
    private String eventId;

    /** The type of event: VehicleUpdated, ContactPreferenceUpdated, or PartyNoteAdded. */
    private String eventType;

    /** Schema version of the payload, e.g. "1.0". */
    private String eventVersion;

    /** Originating domain — expected value: "WorkorderExecution". */
    private String sourceSystem;

    /** Correlation reference to the originating workorder ID. */
    private String correlationId;

    /** ISO 8601 timestamp when the event was generated. */
    private String timestamp;

    /** Event-specific payload data. */
    private Map<String, Object> payload;
}
