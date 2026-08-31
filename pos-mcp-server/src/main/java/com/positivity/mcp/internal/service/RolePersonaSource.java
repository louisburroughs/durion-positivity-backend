package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.RolePersona;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * Where role personas come from (#1613).
 *
 * <p>Declared here rather than depending on {@code internal.client} directly: the client package
 * already depends on this one, and a service-to-client edge would close a package cycle that
 * ArchUnit rejects. The service layer states what it needs; the REST adapter in
 * {@code internal.client} implements it. It also makes the sync runner testable against a stub
 * instead of a mocked {@code RestClient}.
 *
 * <p>Every method is fail-soft and returns {@link Optional#empty()} rather than throwing: a sync
 * failure must leave the previous snapshot serving traffic, never break a request.
 */
public interface RolePersonaSource {

    /** Every role's persona, or empty if the fetch failed. */
    @NonNull
    Optional<RolePersonaSnapshotData> fetchAll();

    /**
     * One role's persona, for the lazy on-miss path — a role created after boot resolves without
     * waiting for the next refresh. Empty when the fetch failed or the role does not exist.
     */
    @NonNull
    Optional<RolePersona> fetchOne(@NonNull String roleName);

    /** A fetched persona list with the upstream timestamp that stamps snapshot age. */
    record RolePersonaSnapshotData(
            @NonNull Instant generatedAt, @NonNull List<RolePersona> personas) {}
}
