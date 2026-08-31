package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.RolePersona;
import com.positivity.mcp.internal.domain.RolePersonaSnapshot;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

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

    /**
     * A refresher whose upstream is permanently unreachable, so every on-miss fetch fails. Models the
     * state most assembly tests care about: no sync has landed and none is coming.
     */
    static RolePersonaRefresher unreachableRefresher(
            SystemPromptRepository repository, RolePersonaSnapshotHolder holder) {
        return new RolePersonaRefresher(new UnreachableSource(), holder, repository);
    }

    /** A resolver with no synced personas and no reachable upstream. */
    static RolePromptResolverImpl resolver(SystemPromptRepository repository, MeterRegistry meterRegistry) {
        return resolver(repository, meterRegistry, emptyHolder());
    }

    /** A resolver reading the given snapshot, with no reachable upstream. */
    static RolePromptResolverImpl resolver(
            SystemPromptRepository repository, MeterRegistry meterRegistry, RolePersonaSnapshotHolder holder) {
        return new RolePromptResolverImpl(repository, meterRegistry, holder, unreachableRefresher(repository, holder));
    }

    private static final class UnreachableSource implements RolePersonaSource {
        @Override
        public Optional<RolePersonaSnapshotData> fetchAll() {
            return Optional.empty();
        }

        @Override
        public Optional<RolePersona> fetchOne(String roleName) {
            return Optional.empty();
        }
    }

    private TestSnapshots() {}
}
