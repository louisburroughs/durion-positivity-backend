package com.positivity.supplier.internal.stockreport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.supplier.SupplierStockReportUpdatedV1;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierStockSnapshot;
import com.positivity.supplier.internal.entity.StockSnapshotEntity;
import com.positivity.supplier.internal.entity.StockSnapshotLineEntity;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.repository.StockSnapshotLineRepository;
import com.positivity.supplier.internal.repository.StockSnapshotRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Stock snapshot persistence and events (#1228)")
class StockReportSnapshotWriterTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final Instant FETCHED_AT = Instant.parse("2026-08-14T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T09:05:00Z"), ZoneOffset.UTC);

    @Mock
    private StockSnapshotRepository snapshotRepository;

    @Mock
    private StockSnapshotLineRepository snapshotLineRepository;

    @Mock
    private SupplierOutboxEventWriter outboxWriter;

    private StockReportSnapshotWriter writer;

    @BeforeEach
    void setUp() {
        writer = new StockReportSnapshotWriter(snapshotRepository, snapshotLineRepository, outboxWriter, CLOCK);
        when(snapshotRepository.save(any(StockSnapshotEntity.class))).thenAnswer(inv -> {
            StockSnapshotEntity entity = inv.getArgument(0);
            if (entity.getSnapshotId() == null) {
                entity.setSnapshotId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"));
            }
            return entity;
        });
        when(snapshotLineRepository.save(any(StockSnapshotLineEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ResolvedBinding binding() {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef("michelin-eu");
        profile.setEnabled(true);

        SupplierEndpointBindingEntity endpointBinding = new SupplierEndpointBindingEntity();
        endpointBinding.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e"));
        endpointBinding.setVendorProfileId(PROFILE_ID);
        endpointBinding.setCapability(SupplierCapability.STOCK_REPORT);
        endpointBinding.setProtocolFamily(ProtocolFamily.EDIWHEEL_B);
        endpointBinding.setProtocolVersion("B2_1");
        endpointBinding.setEnabled(true);

        return new ResolvedBinding(
                profile,
                endpointBinding,
                new SupplierAuthConfigEntity(),
                SupplierCapability.STOCK_REPORT,
                ProtocolFamily.EDIWHEEL_B,
                ProtocolVersion.B2_1);
    }

    private static SupplierAccountEntity billing() {
        SupplierAccountEntity account = new SupplierAccountEntity();
        account.setAccountNumber("30012456");
        account.setAgencyCode("91");
        return account;
    }

    private static SupplierStockSnapshot snapshot(int lineCount, Integer quantity) {
        List<SupplierStockSnapshot.Line> lines = new ArrayList<>();
        IntStream.rangeClosed(1, lineCount)
                .forEach(i -> lines.add(new SupplierStockSnapshot.Line(
                        String.valueOf(i), "352870999908" + i, "99990" + i, "BUY-" + i, "Tyre " + i, quantity)));
        return new SupplierStockSnapshot(
                "STOCKREPORT-1",
                LocalDate.of(2026, 8, 14),
                Instant.parse("2026-08-14T06:00:00Z"),
                "30012456",
                lines,
                List.of(),
                lineCount);
    }

    @SuppressWarnings("unchecked")
    private List<DomainEventEnvelope<?>> capturedEvents() {
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxWriter, org.mockito.Mockito.atLeastOnce()).publish(eq("supplier.events.v1"), captor.capture());
        return captor.getAllValues();
    }

    @Test
    void keepsTheVendorSnapshotTimeSeparateFromTheFetchTime() {
        StockSnapshotEntity row = writer.commit(snapshot(1, 5), binding(), billing(), "corr-1", FETCHED_AT, 500);

        assertThat(row.getSnapshotAsOf()).isEqualTo(Instant.parse("2026-08-14T06:00:00Z"));
        assertThat(row.getFetchedAt()).isEqualTo(FETCHED_AT);
    }

    @Test
    void persistsOneRowPerReportedLine() {
        writer.commit(snapshot(3, 5), binding(), billing(), "corr-1", FETCHED_AT, 500);

        verify(snapshotLineRepository, times(3)).save(any(StockSnapshotLineEntity.class));
    }

    @Test
    void preservesAnUnstatedQuantityAsNullRatherThanZero() {
        writer.commit(snapshot(1, null), binding(), billing(), "corr-1", FETCHED_AT, 500);

        ArgumentCaptor<StockSnapshotLineEntity> captor = ArgumentCaptor.forClass(StockSnapshotLineEntity.class);
        verify(snapshotLineRepository).save(captor.capture());
        assertThat(captor.getValue().getAvailableQuantity()).isNull();

        SupplierStockReportUpdatedV1 payload =
                (SupplierStockReportUpdatedV1) capturedEvents().getFirst().payload();
        assertThat(payload.lines().getFirst().availableQuantity()).isNull();
        assertThat(payload.lines().getFirst().quantityStated()).isFalse();
        assertThat(payload.lines().getFirst().explicitlyOutOfStock()).isFalse();
    }

    @Test
    void publishesAnExplicitZeroAsOutOfStock() {
        writer.commit(snapshot(1, 0), binding(), billing(), "corr-1", FETCHED_AT, 500);

        SupplierStockReportUpdatedV1 payload =
                (SupplierStockReportUpdatedV1) capturedEvents().getFirst().payload();
        assertThat(payload.lines().getFirst().quantityStated()).isTrue();
        assertThat(payload.lines().getFirst().explicitlyOutOfStock()).isTrue();
    }

    @Test
    void chunksLargeSnapshotsAndNumbersEveryChunk() {
        writer.commit(snapshot(7, 5), binding(), billing(), "corr-1", FETCHED_AT, 3);

        List<DomainEventEnvelope<?>> events = capturedEvents();
        assertThat(events).hasSize(3);
        SupplierStockReportUpdatedV1 last =
                (SupplierStockReportUpdatedV1) events.getLast().payload();
        assertThat(last.chunkSequence()).isEqualTo(3);
        assertThat(last.chunkCount()).isEqualTo(3);
        assertThat(last.lines()).hasSize(1);
        assertThat(last.linesReported()).isEqualTo(7);
    }

    @Test
    void keysEveryChunkOnTheSnapshot() {
        writer.commit(snapshot(4, 5), binding(), billing(), "corr-1", FETCHED_AT, 2);

        assertThat(capturedEvents())
                .allSatisfy(event -> assertThat(event.aggregateId())
                        .isEqualTo(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")));
    }

    @Test
    void marksALineLessSnapshotEmptyAndPublishesNothing() {
        StockSnapshotEntity row = writer.commit(snapshot(0, null), binding(), billing(), "corr-1", FETCHED_AT, 500);

        assertThat(row.getStatus()).isEqualTo(StockReportSnapshotWriter.STATUS_EMPTY);
        verify(outboxWriter, never()).publish(any(), any());
    }

    @Test
    void recordsAFailedFetchWithoutPersistingLinesOrPublishing() {
        StockSnapshotEntity row = writer.persistFailure(
                binding(), billing(), "corr-1", FETCHED_AT, "vendor exchange failed: PRE_SEND_FAILURE");

        assertThat(row.getStatus()).isEqualTo(StockReportSnapshotWriter.STATUS_FAILED);
        assertThat(row.getFailureDetail()).contains("PRE_SEND_FAILURE");
        verify(snapshotLineRepository, never()).save(any());
        verify(outboxWriter, never()).publish(any(), any());
    }

    @Test
    void truncatesAnOverlongFailureDetailToItsColumnWidth() {
        StockSnapshotEntity row = writer.persistFailure(binding(), billing(), "corr-1", FETCHED_AT, "x".repeat(5000));

        assertThat(row.getFailureDetail()).hasSize(2000);
    }
}
