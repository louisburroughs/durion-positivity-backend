package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.RoleAuthorities;
import com.positivity.mcp.internal.domain.RolePersona;
import com.positivity.mcp.internal.domain.RolePersonaRenderer;
import com.positivity.mcp.internal.domain.RolePersonaSnapshot;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pulls role personas from {@code pos-security-service} and applies them (#1613, D4).
 *
 * <p>The mechanism, separate from {@link RolePersonaSyncRunner}, which owns only <em>when</em> a
 * pull happens. They are split because the schedule is startup-and-timer work that should not run in
 * tests, while the on-miss path is request-path work that must be available in every profile —
 * folding both into one profile-gated bean would leave prompt assembly unable to repair itself
 * wherever that gate excluded it.
 *
 * <p>Two things are updated on a successful pull, for different reasons:
 *
 * <ul>
 *   <li>the in-memory {@link RolePersonaSnapshot}, which drives resolution priority and the agent
 *       warm-up set;
 *   <li>one {@code system_prompts} row per eligible role, which is what prompt assembly reads. The
 *       rows are why a sync outage is survivable: the last good personas keep serving.
 * </ul>
 *
 * <p>Fail-soft throughout. A failed fetch leaves the previous snapshot and the persisted rows
 * untouched; the gauges on {@link RolePersonaSnapshotHolder} are what make that visible rather than
 * silent.
 */
@Component
public class RolePersonaRefresher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RolePersonaRefresher.class);

    private final RolePersonaSource personaSource;
    private final RolePersonaSnapshotHolder snapshotHolder;
    private final SystemPromptWriter systemPromptWriter;

    public RolePersonaRefresher(
            @NonNull RolePersonaSource personaSource,
            @NonNull RolePersonaSnapshotHolder snapshotHolder,
            @NonNull SystemPromptWriter systemPromptWriter) {
        this.personaSource = personaSource;
        this.snapshotHolder = snapshotHolder;
        this.systemPromptWriter = systemPromptWriter;
    }

    /**
     * Pulls every persona and swaps the snapshot. Returns whether the sync succeeded, so a caller
     * that can react to a failure is able to.
     */
    public boolean refreshAll() {
        Optional<RolePersonaSource.RolePersonaSnapshotData> fetched = personaSource.fetchAll();
        if (fetched.isEmpty()) {
            LOGGER.warn(
                    "MCP role persona sync failed; keeping previous snapshot roleCount={} generatedAt={}",
                    snapshotHolder.get().roleCount(),
                    snapshotHolder.get().generatedAt());
            return false;
        }

        RolePersonaSource.RolePersonaSnapshotData data = fetched.get();
        RolePersonaSnapshot previous = snapshotHolder.get();
        RolePersonaSnapshot snapshot = RolePersonaSnapshot.of(data.generatedAt(), data.personas());
        snapshotHolder.set(snapshot);
        persistPersonas(snapshot);

        LOGGER.info(
                "MCP role persona sync complete roles={} (was {}) ineligible={} generatedAt={}",
                snapshot.roleCount(),
                previous.roleCount(),
                data.personas().size() - snapshot.roleCount(),
                snapshot.generatedAt());
        return true;
    }

    /**
     * Fetches a single role and merges it into the held snapshot (tier 2, the lazy on-miss path).
     *
     * <p>This is what makes a role created after boot work without a restart: the first request from
     * one of its users misses, triggers this, and is served with the persona it just fetched.
     */
    public boolean refreshRole(@NonNull String authority) {
        Optional<RolePersona> fetched = personaSource.fetchOne(RoleAuthorities.toRoleName(authority));
        if (fetched.isEmpty()) {
            return false;
        }

        RolePersona persona = fetched.get();
        applyPersona(persona);
        LOGGER.info(
                "MCP role persona fetched on miss role={} eligible={}", persona.name(), persona.mcpPersonaEligible());
        return true;
    }

    /**
     * Merges one role's persona into the held snapshot and persists its row.
     *
     * <p>Shared by the on-miss fetch and the event listener. An event carries the role's current
     * state rather than a delta, so it can be applied without re-reading upstream — and applying the
     * same event twice is the same as applying it once, which is what makes retries and redeliveries
     * safe without a processed-event table.
     */
    public void applyPersona(@NonNull RolePersona persona) {
        String authority = RoleAuthorities.toAuthority(persona.name());
        if (SystemPromptDefaults.ROLE_USER_PROMPT_NAME.equals(authority)) {
            // A role literally named USER normalizes to ROLE_USER, the key of the built-in fallback
            // persona that every unresolved caller gets. Writing an operator-authored persona over
            // that row would silently repurpose the fallback platform-wide.
            LOGGER.warn(
                    "Ignoring synced role name={} — it collides with the reserved {} fallback identity",
                    persona.name(),
                    SystemPromptDefaults.ROLE_USER_PROMPT_NAME);
            return;
        }
        snapshotHolder.merge(persona);
        if (persona.mcpPersonaEligible()) {
            systemPromptWriter.upsert(authority, RolePersonaRenderer.render(persona));
        } else {
            // A role can go eligible -> ineligible through PUT /v1/roles/{id}. Leaving the row behind
            // would keep serving the persona it is no longer supposed to have, and no later sync
            // would ever remove it.
            systemPromptWriter.remove(authority);
        }
    }

    /**
     * Writes one row per eligible role, and removes the row of any role that is no longer eligible.
     *
     * <p>Deliberately not wrapped in a single transaction: each row is written on its own so one bad
     * persona cannot roll back the rest of the sync, which matters because this runs at startup and
     * a partial refresh is worth more than none.
     *
     * <p>The removal half is not optional. A role can be marked ineligible through
     * {@code PUT /v1/roles/{id}} after it already has a row, and prompt assembly reads that row —
     * so without this sweep the flag would have no effect on any role that once had a persona.
     *
     * <p>Rows for roles that have disappeared upstream entirely are still left alone: this service
     * cannot tell a deleted role from one the projection failed to return, and deleting on that
     * ambiguity would also discard a persona an administrator edited through
     * {@code SystemPromptController}.
     */
    private void persistPersonas(@NonNull RolePersonaSnapshot snapshot) {
        for (String authority : snapshot.rankedAuthorities()) {
            snapshot.personaText(authority).ifPresent(text -> systemPromptWriter.upsert(authority, text));
        }
        for (String authority : snapshot.ineligibleAuthorities()) {
            systemPromptWriter.remove(authority);
        }
    }
}
