package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.entity.BayEntity;
import com.positivity.location.internal.entity.MobileUnitEntity;
import com.positivity.location.internal.repository.BayRepository;
import com.positivity.location.internal.repository.MobileUnitRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link FactBackfillPagePublisher} (ADR-0044 §6, issue #1668).
 */
class FactBackfillPagePublisherTest {

    private static final UUID MIN_UUID = new UUID(0L, 0L);

    private final BayRepository bayRepository = mock(BayRepository.class);
    private final MobileUnitRepository mobileUnitRepository = mock(MobileUnitRepository.class);
    private final LocationFactPublisher locationFactPublisher = mock(LocationFactPublisher.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private final FactBackfillPagePublisher publisher =
            new FactBackfillPagePublisher(bayRepository, mobileUnitRepository, locationFactPublisher, entityManager);

    @Test
    @DisplayName("#1668 publishes a fact for every bay on the page")
    void publishesEveryBayOnThePage() {
        BayEntity first = BayEntity.builder().id(UUID.randomUUID()).build();
        BayEntity second = BayEntity.builder().id(UUID.randomUUID()).build();
        when(bayRepository.findBackfillPage(any(), any())).thenReturn(List.of(first, second));

        List<BayEntity> page = publisher.publishBayPage(null, 500);

        assertThat(page).containsExactly(first, second);
        verify(locationFactPublisher).bayChanged(first);
        verify(locationFactPublisher).bayChanged(second);
    }

    @Test
    @DisplayName("#1668 a null cursor starts the walk at the minimum UUID, so no row is skipped")
    void nullCursorStartsAtMinimumUuid() {
        when(bayRepository.findBackfillPage(any(), any())).thenReturn(List.of());

        publisher.publishBayPage(null, 250);

        ArgumentCaptor<UUID> cursor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(bayRepository).findBackfillPage(cursor.capture(), pageable.capture());
        assertThat(cursor.getValue()).isEqualTo(MIN_UUID);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(250);
    }

    @Test
    @DisplayName("#1668 a supplied cursor is passed straight through as the exclusive lower bound")
    void suppliedCursorIsPassedThrough() {
        UUID afterId = UUID.randomUUID();
        when(bayRepository.findBackfillPage(eq(afterId), any())).thenReturn(List.of());

        publisher.publishBayPage(afterId, 10);

        verify(bayRepository).findBackfillPage(eq(afterId), any());
    }

    @Test
    @DisplayName("#1668 flushes before clearing, so the page's queued outbox rows are not discarded")
    void flushesBeforeClearing() {
        when(bayRepository.findBackfillPage(any(), any()))
                .thenReturn(List.of(BayEntity.builder().id(UUID.randomUUID()).build()));

        publisher.publishBayPage(null, 500);

        // clear() before flush() would detach the pending outbox inserts and the page would commit
        // having published nothing at all.
        InOrder inOrder = inOrder(entityManager);
        inOrder.verify(entityManager).flush();
        inOrder.verify(entityManager).clear();
    }

    @Test
    @DisplayName("#1668 publishes a fact for every mobile unit on the page")
    void publishesEveryMobileUnitOnThePage() {
        MobileUnitEntity unit = MobileUnitEntity.builder().id(UUID.randomUUID()).build();
        when(mobileUnitRepository.findBackfillPage(any(), any())).thenReturn(List.of(unit));

        List<MobileUnitEntity> page = publisher.publishMobileUnitPage(null, 500);

        assertThat(page).containsExactly(unit);
        verify(locationFactPublisher).mobileUnitChanged(unit);
    }
}
