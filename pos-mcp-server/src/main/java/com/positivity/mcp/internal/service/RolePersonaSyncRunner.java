package com.positivity.mcp.internal.service;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Decides <em>when</em> role personas are pulled (#1613, D4 tiers 1 and 3).
 *
 * <p>Replaces {@code SystemPromptSeedRunner.seedRolePersonas}, which wrote one hand-authored persona
 * block per role at startup and so could not see a role created after the release it shipped in.
 *
 * <p>{@link RolePersonaRefresher} does the work; this class only schedules it, which is why the two
 * are separate — the on-miss path must stay available in profiles where a startup runner should not
 * run.
 */
@Component
@Profile("!test")
public class RolePersonaSyncRunner implements ApplicationRunner {

    private final RolePersonaRefresher refresher;

    public RolePersonaSyncRunner(@NonNull RolePersonaRefresher refresher) {
        this.refresher = refresher;
    }

    /** Tier 1: startup pull. */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        refresher.refreshAll();
    }

    /**
     * Tier 3: periodic re-pull.
     *
     * <p>Bounds staleness for the case the on-miss fetch cannot see: a persona edited on a role that
     * is already in the snapshot never misses, so without this it would not land until a restart.
     */
    @Scheduled(
            initialDelayString = "${mcp.role-persona.refresh-interval:PT15M}",
            fixedDelayString = "${mcp.role-persona.refresh-interval:PT15M}")
    public void scheduledRefresh() {
        refresher.refreshAll();
    }
}
