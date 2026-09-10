package com.positivity.poseventreceiver.internal.dao;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.poseventreceiver.internal.entity.EmittedEvent;
import com.positivity.poseventreceiver.internal.repository.EmittedEventRepository;
import com.positivity.poseventreceiver.internal.repository.EventTypeRepository;
import com.positivity.poseventreceiver.internal.repository.PreregisteredEventRepository;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantContextMissingException;
import com.positivity.tenancy.TenantResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code emitted_event} has no row-level security (ADR-0062 exception: TimescaleDB compression and
 * continuous aggregates exclude it), so the {@code tenant_id} column stamped at enqueue time is a
 * row's only tenant. Every queued event must carry the tenant of the request it arrived with, and
 * the unbound batch flush must write it unchanged.
 */
@DisplayName("EventDaoImpl — every queued event carries its request's tenant")
class EventDaoImplTenantTest {

    private final EmittedEventRepository emittedRepo = mock(EmittedEventRepository.class);
    private final List<EmittedEvent> saved = new ArrayList<>();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void flushWritesEveryEventWithTheTenantItWasQueuedUnder() {
        when(emittedRepo.saveAll(anyList())).thenAnswer(invocation -> {
            List<EmittedEvent> batch = invocation.getArgument(0);
            saved.addAll(batch);
            return batch;
        });
        EventDaoImpl dao = dao();

        asTenant(TENANT_A, () -> dao.saveEmittedEvent(event("A_ONE")));
        asTenant(TENANT_B, () -> dao.saveEmittedEvent(event("B_ONE")));
        asTenant(TENANT_A, () -> dao.saveEmittedEvent(event("A_TWO")));

        // Unbound, as the scheduler thread is.
        dao.flushEventBatch();

        assertThat(saved)
                .extracting(EmittedEvent::getId, EmittedEvent::getTenantId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A_ONE", TENANT_A),
                        org.assertj.core.groups.Tuple.tuple("B_ONE", TENANT_B),
                        org.assertj.core.groups.Tuple.tuple("A_TWO", TENANT_A));
        assertThat(TenantContext.isBound())
                .as("the flush leaves no binding behind")
                .isFalse();
    }

    @Test
    void anEventFromAnUnboundRequestIsRefusedRatherThanQueuedWithoutATenant() {
        EventDaoImpl dao = dao();

        assertThatThrownBy(() -> dao.saveEmittedEvent(event("NOBODY")))
                .as("strict tenancy: no default tenant, no binding, no row")
                .isInstanceOf(TenantContextMissingException.class);
    }

    private EventDaoImpl dao() {
        return new EventDaoImpl(
                mock(PreregisteredEventRepository.class),
                emittedRepo,
                mock(EventTypeRepository.class),
                new TenantResolver(new TenancyProperties()));
    }

    private static EmittedEvent event(String id) {
        return new EmittedEvent(id, "1", 1_700_000_000_000L, 5L, Instant.now(), null);
    }
}
