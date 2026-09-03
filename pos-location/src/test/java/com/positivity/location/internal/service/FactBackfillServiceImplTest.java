package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.config.FactBackfillService.BackfillResult;
import com.positivity.location.internal.entity.BayEntity;
import com.positivity.location.internal.entity.MobileUnitEntity;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link FactBackfillServiceImpl} (ADR-0044 §6, issue #1668).
 *
 * <p>The paging walk is the part of the backfill that decides whether a unit is reachable at all, so
 * these cover termination, the resume cursor, and the per-run bound that keeps the walk from
 * outliving the Kafka consumer's poll interval.
 */
class FactBackfillServiceImplTest {

    private final FactBackfillPagePublisher pagePublisher = mock(FactBackfillPagePublisher.class);

    private FactBackfillServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FactBackfillServiceImpl(pagePublisher);
        ReflectionTestUtils.setField(service, "pageSize", 2);
        ReflectionTestUtils.setField(service, "maxRowsPerRun", 100);
    }

    private static BayEntity bay(UUID id) {
        return BayEntity.builder().id(id).name("Bay " + id).build();
    }

    private static MobileUnitEntity unit(UUID id) {
        return MobileUnitEntity.builder().id(id).name("Van " + id).build();
    }

    /** Ids that sort in creation order, so the cursor assertions are meaningful. */
    private static List<UUID> ids(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> UUID.fromString("00000000-0000-0000-0000-%012d".formatted(i + 1)))
                .toList();
    }

    @Test
    @DisplayName("#1668 walks every page until a short page ends the run, and reports the last id")
    void walksAllPagesAndReportsCursor() {
        List<UUID> ids = ids(3);
        when(pagePublisher.publishBayPage(null, 2)).thenReturn(List.of(bay(ids.get(0)), bay(ids.get(1))));
        when(pagePublisher.publishBayPage(ids.get(1), 2)).thenReturn(List.of(bay(ids.get(2))));

        BackfillResult result = service.backfillBays(null);

        assertThat(result.published()).isEqualTo(3);
        assertThat(result.lastId()).isEqualTo(ids.get(2));
        // A short page proves the table is exhausted, so no further query is issued.
        assertThat(result.more()).isFalse();
        verify(pagePublisher).publishBayPage(null, 2);
        verify(pagePublisher).publishBayPage(ids.get(1), 2);
        verifyNoMoreInteractions(pagePublisher);
    }

    @Test
    @DisplayName("#1668 each page resumes from the previous page's last id, never an offset")
    void pagesByKeysetCursor() {
        List<UUID> ids = ids(2);
        when(pagePublisher.publishBayPage(null, 2)).thenReturn(List.of(bay(ids.get(0)), bay(ids.get(1))));
        when(pagePublisher.publishBayPage(ids.get(1), 2)).thenReturn(List.of());

        service.backfillBays(null);

        // Offset paging would skip a surviving row when a row below the offset is deleted mid-run --
        // exactly the invisibility this backfill exists to repair.
        verify(pagePublisher).publishBayPage(ids.get(1), 2);
    }

    @Test
    @DisplayName("#1668 an empty first page is a no-op run, not an error")
    void emptyTableIsNoOp() {
        when(pagePublisher.publishBayPage(any(), eq(2))).thenReturn(List.of());

        BackfillResult result = service.backfillBays(null);

        assertThat(result.published()).isZero();
        assertThat(result.more()).isFalse();
    }

    @Test
    @DisplayName("#1668 a run stops at the per-run bound and reports more work remaining")
    void stopsAtPerRunBound() {
        ReflectionTestUtils.setField(service, "maxRowsPerRun", 2);
        List<UUID> ids = ids(2);
        when(pagePublisher.publishBayPage(null, 2)).thenReturn(List.of(bay(ids.get(0)), bay(ids.get(1))));

        BackfillResult result = service.backfillBays(null);

        // Bounded so the walk cannot outlive max.poll.interval.ms and get the consumer evicted --
        // an eviction never commits the offset, so the same command is redelivered forever.
        assertThat(result.published()).isEqualTo(2);
        assertThat(result.more()).isTrue();
        assertThat(result.lastId()).isEqualTo(ids.get(1));
        // Exactly one page: the bound stops the walk rather than fetching another.
        verify(pagePublisher).publishBayPage(null, 2);
        verifyNoMoreInteractions(pagePublisher);
    }

    @Test
    @DisplayName("#1668 the final page never requests more rows than the run has budget for")
    void lastPageIsClampedToRemainingBudget() {
        ReflectionTestUtils.setField(service, "maxRowsPerRun", 3);
        List<UUID> ids = ids(3);
        when(pagePublisher.publishBayPage(null, 2)).thenReturn(List.of(bay(ids.get(0)), bay(ids.get(1))));
        when(pagePublisher.publishBayPage(ids.get(1), 1)).thenReturn(List.of(bay(ids.get(2))));

        BackfillResult result = service.backfillBays(null);

        assertThat(result.published()).isEqualTo(3);
        verify(pagePublisher).publishBayPage(ids.get(1), 1);
    }

    @Test
    @DisplayName("#1668 a caller-supplied cursor resumes rather than restarting the walk")
    void resumesFromSuppliedCursor() {
        List<UUID> ids = ids(2);
        when(pagePublisher.publishBayPage(ids.get(0), 2)).thenReturn(List.of());

        BackfillResult result = service.backfillBays(ids.get(0));

        assertThat(result.published()).isZero();
        verify(pagePublisher).publishBayPage(ids.get(0), 2);
    }

    @Test
    @DisplayName("#1668 mobile units walk the same way, through their own page publisher")
    void mobileUnitsUseTheirOwnPagePublisher() {
        List<UUID> ids = ids(1);
        when(pagePublisher.publishMobileUnitPage(null, 2)).thenReturn(List.of(unit(ids.get(0))));

        BackfillResult result = service.backfillMobileUnits(null);

        assertThat(result.published()).isEqualTo(1);
        assertThat(result.lastId()).isEqualTo(ids.get(0));
        verify(pagePublisher).publishMobileUnitPage(null, 2);
    }

    @Test
    @DisplayName("#1668 a non-positive page size fails at startup, not when an operator runs a backfill")
    void rejectsMisconfiguredBounds() {
        ReflectionTestUtils.setField(service, "pageSize", 0);

        // Otherwise PageRequest.ofSize(0) throws at command time, the listener's generic handler
        // logs it as a malformed command, and the operator is blamed for a configuration fault.
        assertThatThrownBy(service::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("page-size");

        ReflectionTestUtils.setField(service, "pageSize", 500);
        ReflectionTestUtils.setField(service, "maxRowsPerRun", 0);
        assertThatThrownBy(service::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-rows-per-run");
    }
}
