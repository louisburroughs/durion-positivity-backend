package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.RolePersona;
import com.positivity.mcp.internal.domain.RolePersonaSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** Role persona snapshot fixtures (#1613). */
final class TestSnapshots {

    /**
     * A holder that has never synced. Callers resolve to the {@code ROLE_USER} fallback, which is the
     * correct behaviour for a service whose first sync has not landed yet.
     */
    static RolePersonaSnapshotHolder emptyHolder() {
        return new RolePersonaSnapshotHolder(new SimpleMeterRegistry(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    /** A holder carrying the given personas, ranked in the order supplied. */
    static RolePersonaSnapshotHolder holderWith(RolePersona... personas) {
        RolePersonaSnapshotHolder holder = emptyHolder();
        holder.set(RolePersonaSnapshot.of(Instant.EPOCH, List.of(personas)));
        return holder;
    }

    /** An eligible role at the given rank, with every persona slot left to be derived. */
    static RolePersona eligible(String name, int rank) {
        return new RolePersona(name, null, null, null, null, (short) rank, true);
    }

    private TestSnapshots() {}
}
