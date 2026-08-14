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
import com.positivity.catalog.internal.dto.ProductFactReplayResultDto;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.repository.ProductRepository;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Product-fact replay for replica consumers (#1309, ADR-0044 §4)")
class ProductFactReplayServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CatalogFactPublisher catalogFactPublisher;

    private ProductFactReplayServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductFactReplayServiceImpl(productRepository, catalogFactPublisher, CLOCK);
    }

    private static List<ProductEntity> products(int count) {
        List<ProductEntity> products = new ArrayList<>();
        IntStream.rangeClosed(1, count).forEach(i -> {
            ProductEntity product = new ProductEntity();
            product.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a%02d".formatted(i)));
            product.setSku("SKU-" + i);
            products.add(product);
        });
        return products;
    }

    @Test
    void publishesOneFactPerProductThroughTheOrdinaryPublisher() {
        when(productRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(products(3));

        ProductFactReplayResultDto result = service.replayPage(null, null, 500);

        // The ordinary publisher, not a replay-specific one: a replayed fact must be
        // indistinguishable from a live one, and a second serializer would drift from the first.
        verify(catalogFactPublisher, times(3)).publishProductUpdated(any(ProductEntity.class));
        assertThat(result.emitted()).isEqualTo(3);
    }

    @Test
    void reportsCompleteWhenThePageIsShorterThanTheLimit() {
        when(productRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(products(2));

        ProductFactReplayResultDto result = service.replayPage(null, null, 500);

        assertThat(result.complete()).isTrue();
        assertThat(result.nextAfterId()).isNull();
    }

    @Test
    void returnsTheLastProductIdAsTheCursorWhenAFullPageIsEmitted() {
        List<ProductEntity> page = products(3);
        when(productRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        ProductFactReplayResultDto result = service.replayPage(null, null, 3);

        assertThat(result.complete()).isFalse();
        assertThat(result.nextAfterId()).isEqualTo(page.getLast().getId());
    }

    @Test
    void resumesFromTheSuppliedCursor() {
        UUID cursor = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a05");
        when(productRepository.findForReplay(eq(cursor), isNull(), any(Pageable.class)))
                .thenReturn(products(1));

        service.replayPage(cursor, null, 500);

        verify(productRepository).findForReplay(eq(cursor), isNull(), any(Pageable.class));
    }

    @Test
    void passesTheUpdatedSinceFilterThrough() {
        Instant since = Instant.parse("2026-08-01T00:00:00Z");
        when(productRepository.findForReplay(isNull(), eq(since), any(Pageable.class)))
                .thenReturn(products(1));

        ProductFactReplayResultDto result = service.replayPage(null, since, 500);

        assertThat(result.updatedSince()).isEqualTo(since);
    }

    @Test
    void capsTheRequestedLimitSoAMistypedCallCannotFloodTheBroker() {
        when(productRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(products(1));

        service.replayPage(null, null, 100_000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findForReplay(isNull(), isNull(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(ProductFactReplayServiceImpl.MAX_LIMIT);
    }

    @Test
    void treatsAnEmptyCatalogAsAFinishedReplayRatherThanAnError() {
        when(productRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        ProductFactReplayResultDto result = service.replayPage(null, null, 500);

        assertThat(result.emitted()).isZero();
        assertThat(result.complete()).isTrue();
        assertThat(result.nextAfterId()).isNull();
        verify(catalogFactPublisher, never()).publishProductUpdated(any());
    }
}
