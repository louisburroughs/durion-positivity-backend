package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.config.CatalogFactPublisher;
import com.positivity.catalog.internal.dto.ServiceFactReplayResultDto;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.repository.ServiceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

/**
 * Service-fact replay (#1306, ADR-0044 §4) — the seeding path a consumer's catalog replica needs,
 * without which a service becomes resolvable only when someone next happens to edit it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Service-fact replay for replica consumers (#1306, ADR-0044 §4)")
class ServiceFactReplayServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private CatalogFactPublisher catalogFactPublisher;

    private ServiceFactReplayServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ServiceFactReplayServiceImpl(serviceRepository, catalogFactPublisher, CLOCK);
    }

    private static List<ServiceEntity> services(int count) {
        List<ServiceEntity> services = new ArrayList<>();
        IntStream.rangeClosed(1, count).forEach(i -> {
            ServiceEntity entity = new ServiceEntity();
            entity.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a%02d".formatted(i)));
            entity.setName("Service " + i);
            services.add(entity);
        });
        return services;
    }

    @Test
    void publishesOneFactPerServiceThroughTheOrdinaryPublisher() {
        when(serviceRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(services(3));

        ServiceFactReplayResultDto result = service.replayPage(null, null, 500);

        // The ordinary publisher, not a replay-specific one: a replayed fact must be
        // indistinguishable from a live one, and a second serializer would drift from the first.
        verify(catalogFactPublisher, times(3)).publishServiceUpdated(any(ServiceEntity.class));
        assertThat(result.emitted()).isEqualTo(3);
    }

    @Test
    @DisplayName("a replay only re-emits what exists, never a tombstone for something deleted")
    void neverPublishesRemovals() {
        when(serviceRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(services(2));

        service.replayPage(null, null, 500);

        // A deleted service leaves no row to replay, so its tombstone lives only in the live
        // stream. Synthesising one here would announce a removal the catalog has no record of.
        verify(catalogFactPublisher, never()).publishServiceRemoved(any());
    }

    @Test
    void reportsCompleteWhenThePageIsShorterThanTheLimit() {
        when(serviceRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(services(2));

        ServiceFactReplayResultDto result = service.replayPage(null, null, 500);

        assertThat(result.complete()).isTrue();
        assertThat(result.nextAfterId()).isNull();
    }

    @Test
    void returnsTheLastServiceIdAsTheCursorWhenAFullPageIsEmitted() {
        List<ServiceEntity> page = services(3);
        when(serviceRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        ServiceFactReplayResultDto result = service.replayPage(null, null, 3);

        assertThat(result.complete()).isFalse();
        assertThat(result.nextAfterId()).isEqualTo(page.getLast().getId());
    }

    @Test
    void resumesFromTheSuppliedCursor() {
        UUID cursor = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a05");
        when(serviceRepository.findForReplay(eq(cursor), isNull(), any(Pageable.class)))
                .thenReturn(services(1));

        service.replayPage(cursor, null, 500);

        verify(serviceRepository).findForReplay(eq(cursor), isNull(), any(Pageable.class));
    }

    @Test
    void passesTheUpdatedSinceFilterThrough() {
        Instant since = Instant.parse("2026-08-01T00:00:00Z");
        when(serviceRepository.findForReplay(isNull(), eq(since), any(Pageable.class)))
                .thenReturn(services(1));

        ServiceFactReplayResultDto result = service.replayPage(null, since, 500);

        assertThat(result.updatedSince()).isEqualTo(since);
    }

    @Test
    void capsTheRequestedLimitSoAMistypedCallCannotFloodTheBroker() {
        when(serviceRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(services(1));

        service.replayPage(null, null, 100_000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(serviceRepository).findForReplay(isNull(), isNull(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(ServiceFactReplayServiceImpl.MAX_LIMIT);
    }

    @Test
    void treatsAnEmptyCatalogAsAFinishedReplayRatherThanAnError() {
        when(serviceRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        ServiceFactReplayResultDto result = service.replayPage(null, null, 500);

        assertThat(result.emitted()).isZero();
        assertThat(result.complete()).isTrue();
        assertThat(result.nextAfterId()).isNull();
        verify(catalogFactPublisher, never()).publishServiceUpdated(any());
    }
}
