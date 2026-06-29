package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.WorkorderDetailResponse;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.service.WorkorderDetailServiceImpl;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkorderDetailServiceImpl Unit Tests")
class WorkorderDetailServiceImplTest {

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private TechnicianAssignmentRepository technicianAssignmentRepository;

    @Mock
    private WorkorderLaborEntryRepository laborEntryRepository;

    @Mock
    private WorkorderPartRepository workorderPartRepository;

    @InjectMocks
    private WorkorderDetailServiceImpl service;

    @Test
    @DisplayName("getWorkorderDetail: unassigned workorder returns detail with null assignment fields")
    void getWorkorderDetail_unassignedWorkorder_returnsNullAssignmentFields() {
        UUID workorderId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID customerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID vehicleId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        Workorder workorder = Workorder.builder()
                .id(workorderId)
                .customerId(customerId)
                .vehicleId(vehicleId)
                .shopId(UUID.fromString("44444444-4444-4444-4444-444444444444"))
                .status(WorkorderStatus.APPROVED)
                .updatedAt(Instant.parse("2026-04-18T12:00:00Z"))
                .services(java.util.List.of())
                .build();

        when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
        when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(workorderId))
                .thenReturn(Optional.empty());

        WorkorderDetailResponse response = service.getWorkorderDetail(workorderId, Set.of("workorder:workorder:view"));

        assertThat(response.getWorkorderId()).isEqualTo(workorderId);
        assertThat(response.getAssignedTechnicianId()).isNull();
        assertThat(response.getCapabilities().isCanViewFinancials()).isFalse();
    }

    @Test
    @DisplayName("getWorkorderDetail: returns stored workorder number and includes standalone parts")
    void getWorkorderDetail_returnsWorkorderNumberAndStandaloneParts() {
        UUID workorderId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        Workorder workorder = Workorder.builder()
                .id(workorderId)
                .workorderNumber("WO-2026-1042")
                .customerId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
                .vehicleId(UUID.fromString("33333333-3333-3333-3333-333333333333"))
                .shopId(UUID.fromString("44444444-4444-4444-4444-444444444444"))
                .status(WorkorderStatus.APPROVED)
                .updatedAt(Instant.parse("2026-04-18T12:00:00Z"))
                .services(java.util.List.of())
                .build();

        WorkorderPart standalonePart = WorkorderPart.builder()
                .id(UUID.fromString("66666666-6666-6666-6666-666666666666"))
                .description("Brake pad set")
                .quantity(java.math.BigDecimal.valueOf(2))
                .status(WorkorderItemStatus.OPEN)
                .build();

        when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
        when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(workorderId))
                .thenReturn(Optional.empty());
        when(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(workorderId))
                .thenReturn(java.util.List.of(standalonePart));

        WorkorderDetailResponse response = service.getWorkorderDetail(workorderId, Set.of("workorder:workorder:view"));

        assertThat(response.getWorkorderNumber()).isEqualTo("WO-2026-1042");
        assertThat(response.getParts()).hasSize(1);
        assertThat(response.getParts().get(0).getDescription()).isEqualTo("Brake pad set");
    }
}
