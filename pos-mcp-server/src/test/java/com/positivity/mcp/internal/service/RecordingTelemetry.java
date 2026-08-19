package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Test fixture capturing the {@code nlti.request.telemetry} events an NLTI service emits (#1397).
 *
 * <p>Deliberately a real {@link NltiRequestTelemetryPublisher} over a recording emitter rather than
 * a mock of the publisher: the publisher owns the never-throw contract and the actor resolution, so
 * a mock would assert against a seam that does not exist in production. Its clock is fixed and
 * independent of the clock under test — nothing asserts on the emitted timestamp.
 */
final class RecordingTelemetry {

    private static final Instant TELEMETRY_NOW = Instant.parse("2026-08-19T10:15:30Z");

    private final List<NltiRequestTelemetry> events = new ArrayList<>();
    private final NltiRequestTelemetryPublisher publisher = new NltiRequestTelemetryPublisher(
            events::add, new McpRoleResolverImpl(), Clock.fixed(TELEMETRY_NOW, ZoneOffset.UTC));

    NltiRequestTelemetryPublisher publisher() {
        return publisher;
    }

    List<NltiRequestTelemetry> events() {
        return List.copyOf(events);
    }

    /** The single emitted event, failing loudly when the count is anything but one. */
    NltiRequestTelemetry only() {
        if (events.size() != 1) {
            throw new AssertionError("expected exactly one telemetry event but got " + events.size() + ": " + events);
        }
        return events.get(0);
    }

    /** The events carrying a {@code write.confirmationOutcome}, in emission order. */
    List<NltiRequestTelemetry> withConfirmationOutcome() {
        return events.stream()
                .filter(event -> event.write() != null && event.write().confirmationOutcome() != null)
                .toList();
    }
}
