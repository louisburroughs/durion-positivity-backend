package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.config.AgentOrchestrationService;
import com.positivity.mcp.internal.config.ConversationProperties;
import com.positivity.tenancy.TenantIterator;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * #2073 retention purge: deletes unpinned conversations idle longer than {@code
 * mcp.conversation.retention-days}, with their messages, and drops their cached chat memory. Pinned
 * conversations are exempt (user decision U2).
 *
 * <p>Runs in every profile, every {@code mcp.conversation.purge-interval} (default one hour), per
 * tenant (ADR-0062 §3): {@link TenantIterator#forEachActiveTenant} binds each active tenant in turn
 * and {@link ConversationStore#purgeIdleBatch} opens one short transaction per batch of {@value
 * #PURGE_BATCH_SIZE} inside that binding. The cutoff is computed once per run, so every tenant and
 * batch is purged against the same instant.
 *
 * <p>A batch shorter than {@value #PURGE_BATCH_SIZE} ends the tenant's run. That includes a batch cut
 * short because a locked candidate was touched meanwhile and no longer matched on re-check; any
 * candidates left behind are purged on the next run.
 */
@Component
public class ConversationRetentionScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationRetentionScheduler.class);

    /** Conversations locked, loaded and deleted per purge transaction. */
    static final int PURGE_BATCH_SIZE = 500;

    private final ConversationStore store;
    private final ConversationProperties properties;
    private final TenantIterator tenantIterator;
    private final Clock clock;
    private final ObjectProvider<AgentOrchestrationService> agentOrchestrationService;

    public ConversationRetentionScheduler(
            @NonNull ConversationStore store,
            @NonNull ConversationProperties properties,
            @NonNull TenantIterator tenantIterator,
            @NonNull Clock clock,
            @NonNull ObjectProvider<AgentOrchestrationService> agentOrchestrationService) {
        this.store = store;
        this.properties = properties;
        this.tenantIterator = tenantIterator;
        this.clock = clock;
        this.agentOrchestrationService = agentOrchestrationService;
    }

    @Scheduled(fixedDelayString = "${mcp.conversation.purge-interval:1h}")
    public void purgeIdleConversations() {
        OffsetDateTime cutoff = clock.instant()
                .minus(Duration.ofDays(properties.retentionDays()))
                .atOffset(ZoneOffset.UTC);
        tenantIterator.forEachActiveTenant(tenantId -> {
            int total = 0;
            List<UUID> batch;
            do {
                batch = store.purgeIdleBatch(cutoff, PURGE_BATCH_SIZE);
                evictMemory(batch);
                total += batch.size();
            } while (batch.size() == PURGE_BATCH_SIZE);
            if (total > 0) {
                LOGGER.info(
                        "Purged {} idle conversation(s) tenant={} cutoff={} retentionDays={}",
                        total,
                        tenantId,
                        cutoff,
                        properties.retentionDays());
            }
        });
    }

    private void evictMemory(@NonNull List<UUID> purged) {
        if (purged.isEmpty()) {
            return;
        }
        AgentOrchestrationService orchestration = agentOrchestrationService.getIfAvailable();
        if (orchestration != null) {
            purged.forEach(id -> orchestration.evictConversation(id.toString()));
        }
    }
}
