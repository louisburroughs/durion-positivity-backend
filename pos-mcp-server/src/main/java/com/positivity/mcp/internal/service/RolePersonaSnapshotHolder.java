package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.RolePersona;
import com.positivity.mcp.internal.domain.RolePersonaSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Holds the current {@link RolePersonaSnapshot} and swaps it atomically (#1613).
 *
 * <p>Readers — role resolution, prompt assembly, agent warm-up — take the reference once and work
 * from that instance, so a refresh landing mid-request cannot show them a half-updated role set.
 *
 * <p>Registers two gauges, because the failure this issue is about is silent: a sync that has been
 * failing since boot looks exactly like a quiet one from the outside.
 */
@Component
public class RolePersonaSnapshotHolder {

    /** Eligible roles currently resolvable. Zero after boot means the sync has never succeeded. */
    static final String METRIC_ROLE_COUNT = "mcp.role.persona.count";

    /** Seconds since the held snapshot was generated upstream. Grows without bound if sync stops. */
    static final String METRIC_SNAPSHOT_AGE = "mcp.role.persona.snapshot.age";

    private final AtomicReference<RolePersonaSnapshot> current = new AtomicReference<>(RolePersonaSnapshot.empty());
    private final Clock clock;

    public RolePersonaSnapshotHolder(@NonNull MeterRegistry meterRegistry, @NonNull Clock clock) {
        this.clock = clock;
        meterRegistry.gauge(METRIC_ROLE_COUNT, this, holder -> holder.get().roleCount());
        meterRegistry.gauge(METRIC_SNAPSHOT_AGE, this, RolePersonaSnapshotHolder::snapshotAgeSeconds);
    }

    public @NonNull RolePersonaSnapshot get() {
        return current.get();
    }

    public void set(@NonNull RolePersonaSnapshot snapshot) {
        current.set(snapshot);
    }

    /**
     * Adds one role to the held snapshot, for the lazy on-miss fetch.
     *
     * <p>Rebuilds rather than mutates: the snapshot is immutable and a new role can land anywhere in
     * the rank order, so it has to be re-sorted. Retries on a concurrent swap so a refresh landing at
     * the same moment cannot drop either change.
     */
    public void merge(@NonNull RolePersona persona) {
        current.updateAndGet(snapshot -> {
            List<RolePersona> merged = new ArrayList<>(snapshot.personas());
            merged.removeIf(existing -> existing.name().equalsIgnoreCase(persona.name()));
            merged.add(persona);
            return RolePersonaSnapshot.of(snapshot.generatedAt(), merged);
        });
    }

    private double snapshotAgeSeconds() {
        RolePersonaSnapshot snapshot = current.get();
        if (snapshot.isEmpty()) {
            // No snapshot has ever landed. Reporting age from the epoch would look like an
            // absurdly stale sync rather than an absent one, so report it as a distinct value.
            return -1d;
        }
        return Duration.between(snapshot.generatedAt(), Instant.now(clock)).toSeconds();
    }
}
