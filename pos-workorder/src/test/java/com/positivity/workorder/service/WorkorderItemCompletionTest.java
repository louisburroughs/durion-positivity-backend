package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.WorkorderItemCompletionResponse;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.internal.service.WorkorderServiceImpl;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkorderItemCompletionTest {

    private static final UUID WORKORDER = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID ITEM = UUID.fromString("22222222-0000-0000-0000-000000000001");

    @Mock
    private WorkorderServiceRepository workorderServiceRepository;

    @Mock
    private WorkorderPartRepository workorderPartRepository;

    @InjectMocks
    private WorkorderServiceImpl service;

    private WorkorderServiceLine line(WorkorderItemStatus status, UUID workorderId) {
        return WorkorderServiceLine.builder()
                .id(ITEM)
                .workOrder(
                        workorderId == null
                                ? null
                                : Workorder.builder().id(workorderId).build())
                .status(status)
                .build();
    }

    private WorkorderPart part(WorkorderItemStatus status, UUID workorderId) {
        return WorkorderPart.builder()
                .id(ITEM)
                .workorder(
                        workorderId == null
                                ? null
                                : Workorder.builder().id(workorderId).build())
                .status(status)
                .build();
    }

    @Test
    void completeServiceItem_marksOpenLineCompleted() {
        when(workorderServiceRepository.findById(ITEM))
                .thenReturn(Optional.of(line(WorkorderItemStatus.OPEN, WORKORDER)));

        WorkorderItemCompletionResponse res = service.completeServiceItem(WORKORDER, ITEM, "tech");

        assertThat(res.getStatus()).isEqualTo(WorkorderItemStatus.COMPLETED);
        assertThat(res.getItemType()).isEqualTo(WorkorderItemCompletionResponse.ItemType.SERVICE);
        verify(workorderServiceRepository).save(any());
    }

    @Test
    void completeServiceItem_isIdempotentWhenAlreadyCompleted() {
        when(workorderServiceRepository.findById(ITEM))
                .thenReturn(Optional.of(line(WorkorderItemStatus.COMPLETED, WORKORDER)));

        WorkorderItemCompletionResponse res = service.completeServiceItem(WORKORDER, ITEM, "tech");

        assertThat(res.getStatus()).isEqualTo(WorkorderItemStatus.COMPLETED);
        verify(workorderServiceRepository, never()).save(any());
    }

    @Test
    void completeServiceItem_rejectsCancelled() {
        when(workorderServiceRepository.findById(ITEM))
                .thenReturn(Optional.of(line(WorkorderItemStatus.CANCELLED, WORKORDER)));

        assertThatThrownBy(() -> service.completeServiceItem(WORKORDER, ITEM, "tech"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completeServiceItem_rejectsForeignWorkorder() {
        UUID other = UUID.fromString("99999999-0000-0000-0000-000000000009");
        when(workorderServiceRepository.findById(ITEM)).thenReturn(Optional.of(line(WorkorderItemStatus.OPEN, other)));

        assertThatThrownBy(() -> service.completeServiceItem(WORKORDER, ITEM, "tech"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completePartItem_marksOpenPartCompleted() {
        when(workorderPartRepository.findById(ITEM)).thenReturn(Optional.of(part(WorkorderItemStatus.OPEN, WORKORDER)));

        WorkorderItemCompletionResponse res = service.completePartItem(WORKORDER, ITEM, "tech");

        assertThat(res.getStatus()).isEqualTo(WorkorderItemStatus.COMPLETED);
        assertThat(res.getItemType()).isEqualTo(WorkorderItemCompletionResponse.ItemType.PART);
        verify(workorderPartRepository).save(any());
    }

    @Test
    void completePartItem_rejectsPendingApproval() {
        when(workorderPartRepository.findById(ITEM))
                .thenReturn(Optional.of(part(WorkorderItemStatus.PENDING_APPROVAL, WORKORDER)));

        assertThatThrownBy(() -> service.completePartItem(WORKORDER, ITEM, "tech"))
                .isInstanceOf(IllegalStateException.class);
    }
}
