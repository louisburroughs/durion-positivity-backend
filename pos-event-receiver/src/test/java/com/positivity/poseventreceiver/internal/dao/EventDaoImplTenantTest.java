package com.positivity.poseventreceiver.internal.dao;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.poseventreceiver.internal.entity.EmittedEvent;
import com.positivity.poseventreceiver.internal.repository.EmittedEventRepository;
import com.positivity.poseventreceiver.internal.repository.EventTypeRepository;
import com.positivity.poseventreceiver.internal.repository.PreregisteredEventRepository;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The batch flush runs on the scheduler thread with no tenant bound (ADR-0062 §3): every event must
 * be saved under the tenant it arrived with, not under whatever the flushing thread resolves.
 */
@DisplayName("EventDaoImpl — batch flush keeps each event's tenant")
class EventDaoImplTenantTest {

    private final EmittedEventRepository emittedRepo = mock(EmittedEventRepository.class);
    private final Map<UUID, List<String>> savedByTenant = new ConcurrentHashMap<>();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void flushSavesEveryEventUnderTheTenantItWasQueuedWith() {
        when(emittedRepo.saveAll(anyList())).thenAnswer(invocation -> {
            List<EmittedEvent> batch = invocation.getArgument(0);
            UUID bound = TenantContext.require();
            batch.forEach(event -> savedByTenant
                    .computeIfAbsent(bound, id -> new ArrayList<>())
                    .add(event.getId()));
            return batch;
        });
        EventDaoImpl dao = new EventDaoImpl(
                mock(PreregisteredEventRepository.class),
                emittedRepo,
                mock(EventTypeRepository.class),
                new TenantResolver(new TenancyProperties()));

        asTenant(TENANT_A, () -> dao.saveEmittedEvent(event("A_ONE")));
        asTenant(TENANT_B, () -> dao.saveEmittedEvent(event("B_ONE")));
        asTenant(TENANT_A, () -> dao.saveEmittedEvent(event("A_TWO")));

        // Unbound, as the scheduler thread is.
        dao.flushEventBatch();

        assertThat(savedByTenant.get(TENANT_A)).containsExactly("A_ONE", "A_TWO");
        assertThat(savedByTenant.get(TENANT_B)).containsExactly("B_ONE");
        assertThat(TenantContext.isBound())
                .as("the flush leaves no binding behind")
                .isFalse();
    }

    private static EmittedEvent event(String id) {
        return new EmittedEvent(id, "1", 1_700_000_000_000L, 5L, Instant.now(), null);
    }
}
