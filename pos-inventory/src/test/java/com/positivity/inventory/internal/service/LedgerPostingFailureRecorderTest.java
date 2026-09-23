package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.CycleCountAdjustment;
import com.positivity.inventory.internal.entity.ScrapRecord;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ScrapStatus;
import com.positivity.inventory.internal.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.internal.repository.ScrapRecordRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A failure raised after the ledger post returned rolls that ledger entry back, so the FAILED record
 * must not keep pointing at it (#2170). The transaction boundary itself is covered by
 * {@code LedgerPostingFailurePersistenceIT}; this pins what the recorder writes.
 */
@DisplayName("LedgerPostingFailureRecorder (#2170)")
class LedgerPostingFailureRecorderTest {

    private static final String CAUSE = "adjustment save failed";

    private final CycleCountAdjustmentRepository adjustmentRepository = mock(CycleCountAdjustmentRepository.class);
    private final ScrapRecordRepository scrapRepository = mock(ScrapRecordRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private LedgerPostingFailureRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new LedgerPostingFailureRecorder(
                adjustmentRepository, scrapRepository, mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(recorder, "entityManager", entityManager);
    }

    @Test
    @DisplayName("an existing adjustment is updated FAILED with its posting fields cleared")
    void existingAdjustmentLosesPostingFields() {
        CycleCountAdjustment attempted = postedAdjustment();
        CycleCountAdjustment row = postedAdjustment();
        when(adjustmentRepository.findById(attempted.getAdjustmentId())).thenReturn(Optional.of(row));

        recorder.recordAdjustmentFailure(attempted, CAUSE);

        verify(adjustmentRepository).save(row);
        assertThat(row.getStatus()).isEqualTo(AdjustmentStatus.FAILED);
        assertThat(row.getErrorMessage()).isEqualTo(CAUSE);
        assertThat(row.getLedgerEntryId()).isNull();
        assertThat(row.getPostedAt()).isNull();
    }

    @Test
    @DisplayName("a missing adjustment is inserted FAILED with its posting fields cleared")
    void missingAdjustmentIsInsertedWithoutPostingFields() {
        CycleCountAdjustment attempted = postedAdjustment();
        when(adjustmentRepository.findById(any())).thenReturn(Optional.empty());

        recorder.recordAdjustmentFailure(attempted, CAUSE);

        ArgumentCaptor<CycleCountAdjustment> inserted = ArgumentCaptor.forClass(CycleCountAdjustment.class);
        verify(entityManager).persist(inserted.capture());
        assertThat(inserted.getValue().getAdjustmentId()).isEqualTo(attempted.getAdjustmentId());
        assertThat(inserted.getValue().getStatus()).isEqualTo(AdjustmentStatus.FAILED);
        assertThat(inserted.getValue().getLedgerEntryId()).isNull();
        assertThat(inserted.getValue().getPostedAt()).isNull();
    }

    @Test
    @DisplayName("an existing scrap is updated FAILED with its posting fields cleared")
    void existingScrapLosesPostingFields() {
        ScrapRecord attempted = postedScrap();
        ScrapRecord row = postedScrap();
        when(scrapRepository.findById(attempted.getScrapId())).thenReturn(Optional.of(row));

        recorder.recordScrapFailure(attempted, CAUSE);

        verify(scrapRepository).save(row);
        assertThat(row.getStatus()).isEqualTo(ScrapStatus.FAILED);
        assertThat(row.getLedgerEntryId()).isNull();
        assertThat(row.getPostedAt()).isNull();
    }

    @Test
    @DisplayName("a missing scrap is inserted FAILED with its posting fields cleared")
    void missingScrapIsInsertedWithoutPostingFields() {
        ScrapRecord attempted = postedScrap();
        when(scrapRepository.findById(any())).thenReturn(Optional.empty());

        recorder.recordScrapFailure(attempted, CAUSE);

        ArgumentCaptor<ScrapRecord> inserted = ArgumentCaptor.forClass(ScrapRecord.class);
        verify(entityManager).persist(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo(ScrapStatus.FAILED);
        assertThat(inserted.getValue().getLedgerEntryId()).isNull();
        assertThat(inserted.getValue().getPostedAt()).isNull();
    }

    private static final UUID ADJUSTMENT_ID = UUID.fromString("01960004-0001-7000-8000-000000002171");
    private static final UUID SCRAP_ID = UUID.fromString("01960004-0001-7000-8000-000000002172");

    private static CycleCountAdjustment postedAdjustment() {
        return CycleCountAdjustment.builder()
                .adjustmentId(ADJUSTMENT_ID)
                .status(AdjustmentStatus.POSTED)
                .ledgerEntryId(UUID.randomUUID())
                .postedAt(Instant.parse("2026-09-23T12:00:00Z"))
                .build();
    }

    private static ScrapRecord postedScrap() {
        return ScrapRecord.builder()
                .scrapId(SCRAP_ID)
                .status(ScrapStatus.POSTED)
                .ledgerEntryId(UUID.randomUUID())
                .postedAt(Instant.parse("2026-09-23T12:00:00Z"))
                .build();
    }
}
