package com.positivity.customer.internal.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Inbound event envelope carrying a workorder-originated event to the CRM
 * system.
 *
 * <p>
 * The {@code eventType} field discriminates which event-specific payload
 * is contained in the {@code payload} map.
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventEnvelope {

    /** Unique identifier for this event instance (used for idempotency checks). */
    @JsonProperty("eventId")
    @JsonAlias({"event_id", "eventID", "id"})
    private String eventId;

    /**
     * The type of event: VehicleUpdated, ContactPreferenceUpdated, or
     * PartyNoteAdded.
     */
    @JsonProperty("eventType")
    @JsonAlias({"event_type", "type"})
    private String eventType;

    /** Schema version of the payload, e.g. "1.0". */
    @JsonProperty("eventVersion")
    @JsonAlias({"event_version", "version"})
    private String eventVersion;

    /** Originating domain — expected value: "WorkorderExecution". */
    @JsonProperty("sourceSystem")
    @JsonAlias({"source_system", "source"})
    private String sourceSystem;

    /** Correlation reference to the originating workorder ID. */
    @JsonProperty("correlationId")
    @JsonAlias({"correlation_id", "correlationID", "workorder_id", "workOrderId"})
    private String correlationId;

    /** ISO 8601 timestamp when the event was generated. */
    @JsonProperty("timestamp")
    @JsonAlias({"event_timestamp", "event_time", "timeStamp"})
    private String timestamp;

    /** Event-specific payload data. */
    @JsonProperty("payload")
    @JsonAlias({"body", "data"})
    private Map<String, Object> payload;
}
