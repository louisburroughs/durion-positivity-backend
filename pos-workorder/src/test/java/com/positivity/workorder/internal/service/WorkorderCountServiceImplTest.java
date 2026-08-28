package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.shared.dto.CountResponse;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository.StatusCount;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkorderCountServiceImpl — rung-1 aggregate counts")
class WorkorderCountServiceImplTest {

    @Mock
    private WorkorderRepository workorderRepository;

    @InjectMocks
    private WorkorderCountServiceImpl service;

    private static StatusCount row(WorkorderStatus status, long count) {
        return new StatusCount() {
            @Override
            public WorkorderStatus getStatus() {
                return status;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    @Test
    @DisplayName("countOpen sums non-terminal statuses and excludes COMPLETED/CANCELLED")
    void countOpenSumsNonTerminalStatuses() {
        when(workorderRepository.countGroupedByStatus(WorkorderStatus.getOpenStatuses()))
                .thenReturn(List.of(
                        row(WorkorderStatus.WORK_IN_PROGRESS, 7),
                        row(WorkorderStatus.DRAFT, 3),
                        row(WorkorderStatus.READY_FOR_PICKUP, 2)));

        CountResponse result = service.countOpen();

        assertThat(result.total()).isEqualTo(12);
        assertThat(result.groups())
                .extracting(CountResponse.CountGroup::key)
                .containsExactly("DRAFT", "READY_FOR_PICKUP", "WORK_IN_PROGRESS"); // sorted by key
        assertThat(WorkorderStatus.getOpenStatuses())
                .doesNotContain(WorkorderStatus.COMPLETED, WorkorderStatus.CANCELLED);
    }

    @Test
    @DisplayName("null/empty statuses count every status")
    void nullStatusesCountAllStatuses() {
        ArgumentCaptor<Collection<WorkorderStatus>> captor = ArgumentCaptor.forClass(Collection.class);
        when(workorderRepository.countGroupedByStatus(captor.capture()))
                .thenReturn(List.of(row(WorkorderStatus.COMPLETED, 5)));

        CountResponse result = service.countByStatuses(null);

        assertThat(captor.getValue()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(WorkorderStatus.class));
        assertThat(result.total()).isEqualTo(5);
    }

    @Test
    @DisplayName("explicit status set is passed through and totalled")
    void explicitStatusSetIsCounted() {
        Set<WorkorderStatus> requested = Set.of(WorkorderStatus.AWAITING_PARTS);
        when(workorderRepository.countGroupedByStatus(requested))
                .thenReturn(List.of(row(WorkorderStatus.AWAITING_PARTS, 4)));

        CountResponse result = service.countByStatuses(requested);

        assertThat(result.total()).isEqualTo(4);
        assertThat(result.groups()).singleElement().satisfies(group -> {
            assertThat(group.key()).isEqualTo("AWAITING_PARTS");
            assertThat(group.count()).isEqualTo(4);
        });
    }

    @Test
    @DisplayName("no matching rows yields zero total and empty breakdown")
    void noRowsYieldsZeroTotal() {
        when(workorderRepository.countGroupedByStatus(WorkorderStatus.getOpenStatuses()))
                .thenReturn(List.of());

        CountResponse result = service.countOpen();

        assertThat(result.total()).isZero();
        assertThat(result.groups()).isEmpty();
    }
}
