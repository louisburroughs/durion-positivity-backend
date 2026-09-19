package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.AgentOrchestrationService;
import com.positivity.mcp.internal.config.ConversationProperties;
import com.positivity.tenancy.TenantIterator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link ConversationRetentionScheduler} (#2073, user decision U2): the per-tenant
 * {@link TenantIterator} loop, a single cutoff computed once and reused for every tenant, the
 * per-tenant batching loop (repeats {@link ConversationStore#purgeIdleBatch} until a batch comes
 * back short of {@link ConversationRetentionScheduler#PURGE_BATCH_SIZE}), and chat-memory eviction
 * for every purged conversation id.
 */
@ExtendWith(MockitoExtension.class)
class ConversationRetentionSchedulerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC);
    private static final ConversationProperties PROPERTIES = new ConversationProperties(30, Duration.ofHours(1));

    @Mock
    private ConversationStore store;

    @Mock
    private TenantIterator tenantIterator;

    @Mock
    private ObjectProvider<AgentOrchestrationService> agentOrchestrationServiceProvider;

    @Mock
    private AgentOrchestrationService agentOrchestrationService;

    private ConversationRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ConversationRetentionScheduler(
                store, PROPERTIES, tenantIterator, FIXED_CLOCK, agentOrchestrationServiceProvider);
    }

    @Test
    @DisplayName(
            "purgeIdleConversations runs once per tenant TenantIterator visits, with one cutoff reused for every tenant")
    void purgeIdleConversations_runsPerTenantWithSingleCutoff() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        visitTenants(tenantA, tenantB);
        when(store.purgeIdleBatch(any(OffsetDateTime.class), eq(ConversationRetentionScheduler.PURGE_BATCH_SIZE)))
                .thenReturn(List.of());

        scheduler.purgeIdleConversations();

        ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(store, times(2))
                .purgeIdleBatch(cutoffCaptor.capture(), eq(ConversationRetentionScheduler.PURGE_BATCH_SIZE));
        OffsetDateTime expectedCutoff =
                FIXED_CLOCK.instant().minus(Duration.ofDays(30)).atOffset(ZoneOffset.UTC);
        assertThat(cutoffCaptor.getAllValues())
                .as("the cutoff is computed once per run, not once per tenant")
                .containsOnly(expectedCutoff);
    }

    @Test
    @DisplayName("purgeIdleConversations loops within a tenant until a batch comes back short of PURGE_BATCH_SIZE")
    void purgeIdleConversations_loopsUntilShortBatch() {
        UUID tenant = UUID.randomUUID();
        visitTenants(tenant);
        List<UUID> fullBatch = new ArrayList<>();
        for (int i = 0; i < ConversationRetentionScheduler.PURGE_BATCH_SIZE; i++) {
            fullBatch.add(UUID.randomUUID());
        }
        List<UUID> shortBatch = List.of(UUID.randomUUID());
        when(store.purgeIdleBatch(any(OffsetDateTime.class), eq(ConversationRetentionScheduler.PURGE_BATCH_SIZE)))
                .thenReturn(fullBatch, shortBatch);

        scheduler.purgeIdleConversations();

        // A full-size batch means more may remain; a short batch is the loop's stop signal.
        verify(store, times(2))
                .purgeIdleBatch(any(OffsetDateTime.class), eq(ConversationRetentionScheduler.PURGE_BATCH_SIZE));
    }

    @Test
    @DisplayName("purgeIdleConversations evicts chat memory for every purged conversation id")
    void purgeIdleConversations_evictsMemoryPerPurgedId() {
        UUID tenant = UUID.randomUUID();
        visitTenants(tenant);
        UUID purged1 = UUID.randomUUID();
        UUID purged2 = UUID.randomUUID();
        when(store.purgeIdleBatch(any(OffsetDateTime.class), eq(ConversationRetentionScheduler.PURGE_BATCH_SIZE)))
                .thenReturn(List.of(purged1, purged2));
        when(agentOrchestrationServiceProvider.getIfAvailable()).thenReturn(agentOrchestrationService);

        scheduler.purgeIdleConversations();

        verify(agentOrchestrationService).evictConversation(purged1.toString());
        verify(agentOrchestrationService).evictConversation(purged2.toString());
    }

    @Test
    @DisplayName("purgeIdleConversations with no orchestration wired (non-alpha profile) does not throw")
    void purgeIdleConversations_noOrchestrationWired_doesNotThrow() {
        UUID tenant = UUID.randomUUID();
        visitTenants(tenant);
        when(store.purgeIdleBatch(any(OffsetDateTime.class), eq(ConversationRetentionScheduler.PURGE_BATCH_SIZE)))
                .thenReturn(List.of(UUID.randomUUID()));
        when(agentOrchestrationServiceProvider.getIfAvailable()).thenReturn(null);

        scheduler.purgeIdleConversations();
    }

    @Test
    @DisplayName("purgeIdleConversations with no active tenants never touches the store")
    void purgeIdleConversations_noActiveTenants_neverTouchesStore() {
        when(tenantIterator.forEachActiveTenant(org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);

        scheduler.purgeIdleConversations();

        org.mockito.Mockito.verifyNoInteractions(store);
    }

    /** Stubs {@link TenantIterator#forEachActiveTenant} to synchronously visit exactly {@code tenants}. */
    private void visitTenants(UUID... tenants) {
        doAnswer(invocation -> {
                    Consumer<UUID> work = invocation.getArgument(0);
                    for (UUID tenant : tenants) {
                        work.accept(tenant);
                    }
                    return tenants.length;
                })
                .when(tenantIterator)
                .forEachActiveTenant(any());
    }
}
