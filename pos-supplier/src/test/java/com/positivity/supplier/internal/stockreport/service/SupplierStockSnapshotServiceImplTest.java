package com.positivity.supplier.internal.stockreport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.entity.StockSnapshotEntity;
import com.positivity.supplier.internal.entity.StockSnapshotLineEntity;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.repository.StockSnapshotLineRepository;
import com.positivity.supplier.internal.repository.StockSnapshotRepository;
import com.positivity.supplier.internal.service.model.PagedResponse;
import com.positivity.supplier.internal.stockreport.service.model.StockSnapshotLineView;
import com.positivity.supplier.internal.stockreport.service.model.StockSnapshotSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
@DisplayName("Stock snapshot reads (CAP-322, #1638 decision 5)")
class SupplierStockSnapshotServiceImplTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID SNAPSHOT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");

    @Mock
    private StockSnapshotRepository snapshotRepository;

    @Mock
    private StockSnapshotLineRepository lineRepository;

    private SupplierStockSnapshotServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierStockSnapshotServiceImpl(snapshotRepository, lineRepository);
    }

    private static StockSnapshotEntity snapshot(UUID vendorProfileId) {
        return StockSnapshotEntity.builder()
                .snapshotId(SNAPSHOT_ID)
                .vendorProfileId(vendorProfileId)
                .supplierRef("michelin-eu")
                .protocolVersion("EDIWHEEL_B-2.1")
                .status("COMPLETED")
                .documentId("STOCK-4046266")
                .issuedOn(LocalDate.of(2026, 8, 12))
                .snapshotAsOf(Instant.parse("2026-08-12T04:00:00Z"))
                .buyerAccountNumber("ACC-100")
                .fetchedAt(Instant.parse("2026-08-13T06:00:00Z"))
                .completedAt(Instant.parse("2026-08-13T06:00:41Z"))
                .linesReported(12500)
                .linesRejected(3)
                .correlationId("corr-1")
                .build();
    }

    @Test
    void servesTheLatestSnapshotAsMetadataOnly() {
        when(snapshotRepository.findByVendorProfileIdNewestSnapshotAsOfFirst(PROFILE_ID, PageRequest.of(0, 1)))
                .thenReturn(List.of(snapshot(PROFILE_ID)));

        StockSnapshotSummary summary = service.getLatestSnapshot(PROFILE_ID);

        assertThat(summary.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(summary.status()).isEqualTo("COMPLETED");
        // The two clocks stay distinct: the vendor's own instant and this platform's fetch time.
        assertThat(summary.snapshotAsOf()).isEqualTo(Instant.parse("2026-08-12T04:00:00Z"));
        assertThat(summary.fetchedAt()).isEqualTo(Instant.parse("2026-08-13T06:00:00Z"));
        assertThat(summary.linesReported()).isEqualTo(12500);
        assertThat(summary.linesRejected()).isEqualTo(3);
        assertThat(summary.protocolVersion()).isEqualTo("EDIWHEEL_B-2.1");
    }

    @Test
    void reportsAProfileWithoutSnapshotsAsNotFound() {
        when(snapshotRepository.findByVendorProfileIdNewestSnapshotAsOfFirst(PROFILE_ID, PageRequest.of(0, 1)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.getLatestSnapshot(PROFILE_ID))
                .isInstanceOf(SupplierNotFoundException.class)
                .extracting(ex -> ((SupplierNotFoundException) ex).getCode())
                .isEqualTo(SupplierNotFoundException.STOCK_SNAPSHOT_NOT_FOUND);
    }

    @Test
    void pagesTheLinesOfAnOwnedSnapshotWithTheSearchTextEscaped() {
        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot(PROFILE_ID)));
        StockSnapshotLineEntity line = StockSnapshotLineEntity.builder()
                .lineId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d"))
                .snapshotId(SNAPSHOT_ID)
                .vendorProfileId(PROFILE_ID)
                .vendorLineId("417")
                .articleEan("3528709999083")
                .supplierArticleCode("999908")
                .description("MICHELIN PILOT SPORT 5")
                .availableQuantity(null)
                .build();
        // Lowercased and LIKE-escaped: a pasted vendor code containing '_' is a literal.
        when(lineRepository.searchBySnapshotId(eq(SNAPSHOT_ID), eq("%999!_90%"), eq(PageRequest.of(0, 50))))
                .thenReturn(new PageImpl<>(List.of(line), PageRequest.of(0, 50), 1));

        PagedResponse<StockSnapshotLineView> result =
                service.listSnapshotLines(PROFILE_ID, SNAPSHOT_ID, "999_90", 0, 50);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(view -> {
            assertThat(view.articleEan()).isEqualTo("3528709999083");
            // Null quantity survives the mapping: "the vendor said nothing" must never
            // become "the vendor said none".
            assertThat(view.availableQuantity()).isNull();
        });
    }

    @Test
    void treatsABlankLineSearchAsNoSearchAtAll() {
        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot(PROFILE_ID)));
        when(lineRepository.searchBySnapshotId(eq(SNAPSHOT_ID), isNull(), eq(PageRequest.of(0, 50))))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        assertThat(service.listSnapshotLines(PROFILE_ID, SNAPSHOT_ID, "  ", 0, 50)
                        .items())
                .isEmpty();
    }

    @Test
    void reportsAnUnknownSnapshotAsNotFound() {
        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listSnapshotLines(PROFILE_ID, SNAPSHOT_ID, null, 0, 50))
                .isInstanceOf(SupplierNotFoundException.class);
        verify(lineRepository, never()).searchBySnapshotId(any(), any(), any());
    }

    @Test
    void refusesToServeAnotherProfilesSnapshot() {
        // Same code as "no such snapshot": confirming the id exists under another profile would
        // leak another trading relationship's fetch history.
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.of(snapshot(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4aff"))));

        assertThatThrownBy(() -> service.listSnapshotLines(PROFILE_ID, SNAPSHOT_ID, null, 0, 50))
                .isInstanceOf(SupplierNotFoundException.class)
                .extracting(ex -> ((SupplierNotFoundException) ex).getCode())
                .isEqualTo(SupplierNotFoundException.STOCK_SNAPSHOT_NOT_FOUND);
        verify(lineRepository, never()).searchBySnapshotId(any(), any(), any());
    }
}
