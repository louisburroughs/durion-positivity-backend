package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.WorkorderNumberRef;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.service.CustomerReferenceService;
import com.positivity.workorder.internal.service.VehicleReferenceService;
import com.positivity.workorder.internal.service.WorkorderSearchServiceImpl;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the batch workorder-number resolution used by sibling services
 * (e.g. pos-invoice) to enrich finder rows with the human workorder number.
 */
@ExtendWith(MockitoExtension.class)
class WorkorderNumberResolveTest {

    private static final UUID WORKORDER_A = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID WORKORDER_B = UUID.fromString("11111111-0000-0000-0000-000000000002");

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private CustomerReferenceService customerReferenceService;

    @Mock
    private VehicleReferenceService vehicleReferenceService;

    @InjectMocks
    private WorkorderSearchServiceImpl workorderSearchService;

    private Workorder workorder(UUID id, String number) {
        return Workorder.builder()
                .id(id)
                .workorderNumber(number)
                .status(WorkorderStatus.DRAFT)
                .build();
    }

    @Test
    void resolveNumbers_returnsIdToNumberPairings() {
        when(workorderRepository.findAllById(List.of(WORKORDER_A, WORKORDER_B)))
                .thenReturn(List.of(workorder(WORKORDER_A, "WO-2026-1001"), workorder(WORKORDER_B, "WO-2026-1002")));

        List<WorkorderNumberRef> refs = workorderSearchService.resolveNumbers(List.of(WORKORDER_A, WORKORDER_B));

        assertThat(refs)
                .extracting(WorkorderNumberRef::workorderId, WorkorderNumberRef::workorderNumber)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(WORKORDER_A, "WO-2026-1001"),
                        org.assertj.core.groups.Tuple.tuple(WORKORDER_B, "WO-2026-1002"));
    }

    @Test
    void resolveNumbers_emptyInput_returnsEmptyWithoutRepositoryCall() {
        List<WorkorderNumberRef> refs = workorderSearchService.resolveNumbers(List.of());
        assertThat(refs).isEmpty();
    }
}
