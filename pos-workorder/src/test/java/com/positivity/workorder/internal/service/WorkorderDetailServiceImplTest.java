package com.positivity.workorder.internal.service;

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

    @Mock
    private EstimatedLaborService estimatedLaborService;

    @InjectMocks
    private WorkorderDetailServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void stubEstimatedLabor() {
        // Answers "no estimate" unless a case stubs otherwise — pre-#1569 scenarios unchanged.
        org.mockito.Mockito.lenient()
                .when(estimatedLaborService.estimateForLines(org.mockito.ArgumentMatchers.any()))
                .thenReturn(EstimatedLaborService.EstimatedLabor.none());
    }

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

    // -----------------------------------------------------------------------
    // #1569 scope item 7: estimate-vs-actual labor block on the detail view
    // -----------------------------------------------------------------------

    private Workorder workorderWithOneServiceLine(UUID workorderId, UUID serviceLineId) {
        com.positivity.workorder.internal.entity.WorkorderServiceLine line =
                com.positivity.workorder.internal.entity.WorkorderServiceLine.builder()
                        .id(serviceLineId)
                        .description("Front brake pads")
                        .quantity(new java.math.BigDecimal("2.0"))
                        .status(WorkorderItemStatus.OPEN)
                        .build();
        return Workorder.builder()
                .id(workorderId)
                .customerId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
                .vehicleId(UUID.fromString("33333333-3333-3333-3333-333333333333"))
                .shopId(UUID.fromString("44444444-4444-4444-4444-444444444444"))
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .updatedAt(Instant.parse("2026-09-02T09:00:00Z"))
                .services(java.util.List.of(line))
                .build();
    }

    private void stubClockedHours(UUID serviceLineId, String hours) {
        // Build the entry first: the entity's @NonNull builder fields must all be present
        // before the stubbing call, or a failed build() leaves the stubbing unfinished.
        com.positivity.workorder.internal.entity.WorkorderLaborEntry entry =
                com.positivity.workorder.internal.entity.WorkorderLaborEntry.builder()
                        .workorder(Workorder.builder()
                                .id(UUID.fromString("88888888-8888-8888-8888-888888888888"))
                                .build())
                        .workorderService(com.positivity.workorder.internal.entity.WorkorderServiceLine.builder()
                                .id(serviceLineId)
                                .build())
                        .technicianId(UUID.fromString("99999999-9999-9999-9999-999999999999"))
                        .startTime(java.time.LocalDateTime.of(2026, 9, 2, 8, 0))
                        .hoursWorked(new java.math.BigDecimal(hours))
                        .createdBy("jane.smith")
                        .build();
        when(laborEntryRepository.findByWorkorderService_IdOrderByStartTimeDesc(serviceLineId))
                .thenReturn(java.util.List.of(entry));
    }

    @Test
    @DisplayName("#1569: estimated, actual, and both variance fields populate when an estimate exists")
    void getWorkorderDetail_populatesLaborVarianceBlock() {
        UUID workorderId = UUID.fromString("77777777-7777-7777-7777-777777777771");
        UUID serviceLineId = UUID.fromString("77777777-7777-7777-7777-777777777772");
        Workorder workorder = workorderWithOneServiceLine(workorderId, serviceLineId);

        when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
        when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(workorderId))
                .thenReturn(Optional.empty());
        stubClockedHours(serviceLineId, "2.5");
        when(estimatedLaborService.estimateForLines(workorder.getServices()))
                .thenReturn(
                        new EstimatedLaborService.EstimatedLabor(new java.math.BigDecimal("2.0"), java.util.List.of()));

        WorkorderDetailResponse response = service.getWorkorderDetail(workorderId, Set.of("workorder:workorder:view"));

        assertThat(response.getEstimatedLaborHours()).isEqualByComparingTo("2.0");
        assertThat(response.getActualLaborHours()).isEqualByComparingTo("2.5");
        assertThat(response.getLaborVarianceHours()).isEqualByComparingTo("0.5");
        // 0.5 over 2.0 = +25.0%
        assertThat(response.getLaborVariancePct()).isEqualByComparingTo("25.0");
    }

    @Test
    @DisplayName("#1569: a zero-hours estimate yields variance hours but a null percentage — no divide by zero")
    void getWorkorderDetail_zeroEstimate_variancePctNull() {
        UUID workorderId = UUID.fromString("77777777-7777-7777-7777-777777777773");
        UUID serviceLineId = UUID.fromString("77777777-7777-7777-7777-777777777774");
        Workorder workorder = workorderWithOneServiceLine(workorderId, serviceLineId);

        when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
        when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(workorderId))
                .thenReturn(Optional.empty());
        stubClockedHours(serviceLineId, "1.5");
        when(estimatedLaborService.estimateForLines(workorder.getServices()))
                .thenReturn(new EstimatedLaborService.EstimatedLabor(java.math.BigDecimal.ZERO, java.util.List.of()));

        WorkorderDetailResponse response = service.getWorkorderDetail(workorderId, Set.of("workorder:workorder:view"));

        assertThat(response.getEstimatedLaborHours()).isEqualByComparingTo("0");
        assertThat(response.getLaborVarianceHours()).isEqualByComparingTo("1.5");
        assertThat(response.getLaborVariancePct()).isNull();
    }

    @Test
    @DisplayName("#1569: no estimate means null estimated and variance fields, but actual hours still report")
    void getWorkorderDetail_noEstimate_varianceFieldsNull() {
        UUID workorderId = UUID.fromString("77777777-7777-7777-7777-777777777775");
        UUID serviceLineId = UUID.fromString("77777777-7777-7777-7777-777777777776");
        Workorder workorder = workorderWithOneServiceLine(workorderId, serviceLineId);

        when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
        when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(workorderId))
                .thenReturn(Optional.empty());
        stubClockedHours(serviceLineId, "1.5");
        // BeforeEach already stubs estimateForLines to EstimatedLabor.none() (null hours).

        WorkorderDetailResponse response = service.getWorkorderDetail(workorderId, Set.of("workorder:workorder:view"));

        assertThat(response.getEstimatedLaborHours()).isNull();
        assertThat(response.getActualLaborHours()).isEqualByComparingTo("1.5");
        assertThat(response.getLaborVarianceHours()).isNull();
        assertThat(response.getLaborVariancePct()).isNull();
    }
}
