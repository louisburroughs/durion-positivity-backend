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
import com.positivity.catalog.internal.dto.SupplierArticleCodeReplayResultDto;
import com.positivity.catalog.internal.entity.SupplierArticleCodeEntity;
import com.positivity.catalog.internal.repository.SupplierArticleCodeRepository;
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
@DisplayName("Vendor-article-code fact replay for replica consumers (CAP-320 #1347)")
class SupplierArticleCodeReplayServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-17T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private SupplierArticleCodeRepository supplierArticleCodeRepository;

    @Mock
    private CatalogFactPublisher catalogFactPublisher;

    private SupplierArticleCodeReplayServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierArticleCodeReplayServiceImpl(supplierArticleCodeRepository, catalogFactPublisher, CLOCK);
    }

    private static List<SupplierArticleCodeEntity> rows(int count) {
        List<SupplierArticleCodeEntity> rows = new ArrayList<>();
        IntStream.rangeClosed(1, count)
                .forEach(i -> rows.add(SupplierArticleCodeEntity.builder()
                        .id(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a%02d".formatted(i)))
                        .vendorProfileId(UUID.randomUUID())
                        .supplierRef("michelin-eu")
                        .productId(UUID.randomUUID())
                        .supplierArticleCode("CODE-" + i)
                        .build()));
        return rows;
    }

    @Test
    void publishesOneFactPerRowThroughTheOrdinaryPublisher() {
        when(supplierArticleCodeRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(rows(3));

        SupplierArticleCodeReplayResultDto result = service.replayPage(null, null, 500);

        verify(catalogFactPublisher, times(3)).publishSupplierArticleCodeUpdated(any(SupplierArticleCodeEntity.class));
        assertThat(result.emitted()).isEqualTo(3);
    }

    @Test
    void reportsCompleteWhenThePageIsShorterThanTheLimit() {
        when(supplierArticleCodeRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(rows(2));

        SupplierArticleCodeReplayResultDto result = service.replayPage(null, null, 500);

        assertThat(result.complete()).isTrue();
        assertThat(result.nextAfterId()).isNull();
    }

    @Test
    void returnsTheLastRowIdAsTheCursorWhenAFullPageIsEmitted() {
        List<SupplierArticleCodeEntity> page = rows(3);
        when(supplierArticleCodeRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        SupplierArticleCodeReplayResultDto result = service.replayPage(null, null, 3);

        assertThat(result.complete()).isFalse();
        assertThat(result.nextAfterId()).isEqualTo(page.getLast().getId());
    }

    @Test
    void resumesFromTheSuppliedCursor() {
        UUID cursor = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a05");
        when(supplierArticleCodeRepository.findForReplay(eq(cursor), isNull(), any(Pageable.class)))
                .thenReturn(rows(1));

        service.replayPage(cursor, null, 500);

        verify(supplierArticleCodeRepository).findForReplay(eq(cursor), isNull(), any(Pageable.class));
    }

    @Test
    void capsTheRequestedLimitSoAMistypedCallCannotFloodTheBroker() {
        when(supplierArticleCodeRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(rows(1));

        service.replayPage(null, null, 100_000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(supplierArticleCodeRepository).findForReplay(isNull(), isNull(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(SupplierArticleCodeReplayServiceImpl.MAX_LIMIT);
    }

    @Test
    void treatsAnEmptyTableAsAFinishedReplayRatherThanAnError() {
        when(supplierArticleCodeRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        SupplierArticleCodeReplayResultDto result = service.replayPage(null, null, 500);

        assertThat(result.emitted()).isZero();
        assertThat(result.complete()).isTrue();
        assertThat(result.nextAfterId()).isNull();
        verify(catalogFactPublisher, never()).publishSupplierArticleCodeUpdated(any());
    }
}
