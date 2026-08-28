package com.positivity.inventory.internal.cyclecount.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.cyclecount.CycleCountTaskResponse;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountTaskGenerationResponse;
import com.positivity.inventory.internal.dto.cyclecount.plan.GenerateCycleCountTasksRequest;
import com.positivity.inventory.internal.entity.CycleCountPlan;
import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import com.positivity.inventory.internal.enums.TaskStatus;
import com.positivity.inventory.internal.exception.CycleCountPlanNotFoundException;
import com.positivity.inventory.internal.repository.CycleCountPlanRepository;
import com.positivity.inventory.internal.repository.CycleCountTaskRepository;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.service.BaseUnitOfMeasureResolver;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for CycleCountTaskGenerationServiceImpl: plan → task expansion
 * (ledger on-hand snapshot, zone/site scope resolution, replica descendant
 * traversal), per-(plan, bin, SKU) idempotency against preloaded existing
 * tasks, the PLANNED → STARTED transition through the event-emitting
 * startForTaskGeneration path, and the empty-scope behavior (no tasks → plan
 * stays PLANNED).
 */
@ExtendWith(MockitoExtension.class)
class CycleCountTaskGenerationServiceImplTest {

    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ZONE_A = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID BIN_A1 = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final String AUDITOR = "auditor-1";

    @Mock
    private CycleCountPlanRepository planRepository;

    @Mock
    private CycleCountTaskRepository taskRepository;

    @Mock
    private CycleCountPlanService planService;

    @Mock
    private InventoryLedgerEntryRepository ledgerRepository;

    @Mock
    private ExtStorageLocationReplicaRepository storageLocationRepository;

    @Mock
    private BaseUnitOfMeasureResolver baseUnitOfMeasureResolver;

    private CycleCountTaskGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CycleCountTaskGenerationServiceImpl(
                planRepository,
                taskRepository,
                planService,
                ledgerRepository,
                storageLocationRepository,
                baseUnitOfMeasureResolver,
                new CycleCountTaskResponseMapper(baseUnitOfMeasureResolver));
    }

    private CycleCountPlan plan(CycleCountPlanStatus status, List<UUID> zoneIds) {
        return CycleCountPlan.builder()
                .planId(PLAN_ID)
                .locationId(SITE_ID)
                .zoneIds(zoneIds)
                .planName("test plan")
                .status(status)
                .createdBy("user-1")
                .build();
    }

    private InventoryLedgerEntryRepository.LocationOnHand onHand(String sku, String quantity) {
        return new InventoryLedgerEntryRepository.LocationOnHand() {
            @Override
            public String getStockItemId() {
                return sku;
            }

            @Override
            public BigDecimal getOnHandQuantity() {
                return new BigDecimal(quantity);
            }
        };
    }

    private ExtStorageLocationReplica replica(UUID id, UUID parentId) {
        return ExtStorageLocationReplica.builder()
                .storageLocationId(id)
                .siteId(SITE_ID)
                .parentStorageLocationId(parentId)
                .build();
    }

    private CycleCountTask existingTask(String binLocation, String sku) {
        return CycleCountTask.builder()
                .taskId(UUID.fromString("00000000-0000-0000-0000-000000000098"))
                .planId(PLAN_ID)
                .binLocation(binLocation)
                .itemSku(sku)
                .expectedQuantity(BigDecimal.ONE)
                .auditorId(AUDITOR)
                .status(TaskStatus.ASSIGNED)
                .build();
    }

    private void stubSaveAllEcho() {
        when(taskRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private CycleCountPlanResponse startedPlanResponse() {
        return CycleCountPlanResponse.builder()
                .planId(PLAN_ID)
                .status(CycleCountPlanStatus.STARTED.name())
                .build();
    }

    // ─── generateTasks ────────────────────────────────────────────────────────

    @Test
    void generatesAssignedTasksFromLedgerOnHandAndStartsPlan() {
        when(planRepository.findWithLockByPlanId(PLAN_ID))
                .thenReturn(Optional.of(plan(CycleCountPlanStatus.PLANNED, List.of(ZONE_A))));
        when(storageLocationRepository.findByParentStorageLocationIdIn(anyCollection()))
                .thenReturn(List.of());
        when(taskRepository.findByPlanIdOrderByCreatedAtAscTaskIdAsc(PLAN_ID)).thenReturn(List.of());
        when(ledgerRepository.findPositiveOnHandByLocation(eq(ZONE_A), anyCollection()))
                .thenReturn(List.of(onHand("OIL-5W30-5QT", "24"), onHand("WIXF-57356", "12.5")));
        stubSaveAllEcho();
        when(planService.startForTaskGeneration(PLAN_ID)).thenReturn(startedPlanResponse());

        CycleCountTaskGenerationResponse response =
                service.generateTasks(PLAN_ID, new GenerateCycleCountTasksRequest(AUDITOR));

        assertThat(response.getPlanId()).isEqualTo(PLAN_ID);
        assertThat(response.getPlanStatus()).isEqualTo("STARTED");
        assertThat(response.getLocationsScanned()).isEqualTo(1);
        assertThat(response.getTasksCreated()).isEqualTo(2);
        assertThat(response.getTasksSkippedExisting()).isZero();
        assertThat(response.getTasks()).hasSize(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CycleCountTask>> saved = ArgumentCaptor.forClass(List.class);
        verify(taskRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        CycleCountTask first = saved.getValue().get(0);
        assertThat(first.getPlanId()).isEqualTo(PLAN_ID);
        assertThat(first.getBinLocation()).isEqualTo(ZONE_A.toString());
        assertThat(first.getItemSku()).isEqualTo("OIL-5W30-5QT");
        assertThat(first.getExpectedQuantity()).isEqualByComparingTo("24");
        assertThat(first.getAuditorId()).isEqualTo(AUDITOR);
        assertThat(first.getStatus()).isEqualTo(TaskStatus.ASSIGNED);
        assertThat(saved.getValue().get(1).getExpectedQuantity()).isEqualByComparingTo("12.5");
    }

    @Test
    void skipsPairsThatAlreadyHaveATaskForThePlan() {
        when(planRepository.findWithLockByPlanId(PLAN_ID))
                .thenReturn(Optional.of(plan(CycleCountPlanStatus.STARTED, List.of(ZONE_A))));
        when(storageLocationRepository.findByParentStorageLocationIdIn(anyCollection()))
                .thenReturn(List.of());
        when(taskRepository.findByPlanIdOrderByCreatedAtAscTaskIdAsc(PLAN_ID))
                .thenReturn(List.of(existingTask(ZONE_A.toString(), "OIL-5W30-5QT")));
        when(ledgerRepository.findPositiveOnHandByLocation(eq(ZONE_A), anyCollection()))
                .thenReturn(List.of(onHand("OIL-5W30-5QT", "24")));

        CycleCountTaskGenerationResponse response =
                service.generateTasks(PLAN_ID, new GenerateCycleCountTasksRequest(AUDITOR));

        assertThat(response.getTasksCreated()).isZero();
        assertThat(response.getTasksSkippedExisting()).isEqualTo(1);
        verify(taskRepository, never()).saveAll(anyList());
        // A STARTED plan stays STARTED — no lifecycle call.
        verify(planService, never()).startForTaskGeneration(any());
        assertThat(response.getPlanStatus()).isEqualTo("STARTED");
    }

    @Test
    void expandsZonesThroughReplicatedDescendants() {
        when(planRepository.findWithLockByPlanId(PLAN_ID))
                .thenReturn(Optional.of(plan(CycleCountPlanStatus.STARTED, List.of(ZONE_A))));
        when(storageLocationRepository.findByParentStorageLocationIdIn(anyCollection()))
                .thenAnswer(inv -> {
                    Collection<?> parents = inv.getArgument(0);
                    return parents.contains(ZONE_A)
                            ? List.of(replica(BIN_A1, ZONE_A))
                            : List.<ExtStorageLocationReplica>of();
                });
        when(taskRepository.findByPlanIdOrderByCreatedAtAscTaskIdAsc(PLAN_ID)).thenReturn(List.of());
        when(ledgerRepository.findPositiveOnHandByLocation(eq(ZONE_A), anyCollection()))
                .thenReturn(List.of());
        when(ledgerRepository.findPositiveOnHandByLocation(eq(BIN_A1), anyCollection()))
                .thenReturn(List.of(onHand("WIXF-57356", "12")));
        stubSaveAllEcho();

        CycleCountTaskGenerationResponse response =
                service.generateTasks(PLAN_ID, new GenerateCycleCountTasksRequest(AUDITOR));

        assertThat(response.getLocationsScanned()).isEqualTo(2);
        assertThat(response.getTasksCreated()).isEqualTo(1);
        assertThat(response.getTasks().get(0).getBinLocation()).isEqualTo(BIN_A1.toString());
    }

    @Test
    void zonelessPlanScansSiteReplicasAndFallsBackToPlanLocation() {
        // With replicas: site lookup seeds the scope. Nothing stocked → plan stays STARTED as-is.
        when(planRepository.findWithLockByPlanId(PLAN_ID))
                .thenReturn(Optional.of(plan(CycleCountPlanStatus.STARTED, List.of())));
        when(storageLocationRepository.findBySiteId(SITE_ID)).thenReturn(List.of(replica(BIN_A1, null)));
        when(storageLocationRepository.findByParentStorageLocationIdIn(anyCollection()))
                .thenReturn(List.of());
        when(taskRepository.findByPlanIdOrderByCreatedAtAscTaskIdAsc(PLAN_ID)).thenReturn(List.of());
        when(ledgerRepository.findPositiveOnHandByLocation(eq(BIN_A1), anyCollection()))
                .thenReturn(List.of());

        assertThat(service.generateTasks(PLAN_ID, new GenerateCycleCountTasksRequest(AUDITOR))
                        .getLocationsScanned())
                .isEqualTo(1);

        // Without replicas: the plan's own location id is the single count location.
        when(storageLocationRepository.findBySiteId(SITE_ID)).thenReturn(List.of());
        when(ledgerRepository.findPositiveOnHandByLocation(eq(SITE_ID), anyCollection()))
                .thenReturn(List.of());

        CycleCountTaskGenerationResponse fallback =
                service.generateTasks(PLAN_ID, new GenerateCycleCountTasksRequest(AUDITOR));
        assertThat(fallback.getLocationsScanned()).isEqualTo(1);
        verify(ledgerRepository).findPositiveOnHandByLocation(eq(SITE_ID), anyCollection());
    }

    @Test
    void emptyScopeLeavesPlannedPlanPlanned() {
        when(planRepository.findWithLockByPlanId(PLAN_ID))
                .thenReturn(Optional.of(plan(CycleCountPlanStatus.PLANNED, List.of(ZONE_A))));
        when(storageLocationRepository.findByParentStorageLocationIdIn(anyCollection()))
                .thenReturn(List.of());
        when(taskRepository.findByPlanIdOrderByCreatedAtAscTaskIdAsc(PLAN_ID)).thenReturn(List.of());
        when(ledgerRepository.findPositiveOnHandByLocation(eq(ZONE_A), anyCollection()))
                .thenReturn(List.of());

        CycleCountTaskGenerationResponse response =
                service.generateTasks(PLAN_ID, new GenerateCycleCountTasksRequest(AUDITOR));

        // No stocked pair anywhere: nothing created, and the plan must NOT be started —
        // an empty count in progress would be a lie.
        assertThat(response.getTasksCreated()).isZero();
        assertThat(response.getTasksSkippedExisting()).isZero();
        assertThat(response.getPlanStatus()).isEqualTo("PLANNED");
        verify(planService, never()).startForTaskGeneration(any());
        verify(taskRepository, never()).saveAll(anyList());
    }

    @Test
    void rejectsGenerationOutsidePlannedOrStarted() {
        when(planRepository.findWithLockByPlanId(PLAN_ID))
                .thenReturn(Optional.of(plan(CycleCountPlanStatus.COMPLETED_PENDING_APPROVAL, List.of(ZONE_A))));

        assertThatThrownBy(() -> service.generateTasks(PLAN_ID, new GenerateCycleCountTasksRequest(AUDITOR)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED_PENDING_APPROVAL");
        verify(taskRepository, never()).saveAll(anyList());
    }

    @Test
    void unknownPlanThrowsNotFound() {
        when(planRepository.findWithLockByPlanId(PLAN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateTasks(PLAN_ID, new GenerateCycleCountTasksRequest(AUDITOR)))
                .isInstanceOf(CycleCountPlanNotFoundException.class);
    }

    // ─── getTasksForPlan ──────────────────────────────────────────────────────

    @Test
    void listsTasksForPlanInCreationOrder() {
        when(planRepository.existsById(PLAN_ID)).thenReturn(true);
        when(taskRepository.findByPlanIdOrderByCreatedAtAscTaskIdAsc(PLAN_ID))
                .thenReturn(List.of(CycleCountTask.builder()
                        .taskId(UUID.fromString("00000000-0000-0000-0000-000000000099"))
                        .planId(PLAN_ID)
                        .binLocation(ZONE_A.toString())
                        .itemSku("OIL-5W30-5QT")
                        .expectedQuantity(new BigDecimal("24"))
                        .auditorId(AUDITOR)
                        .status(TaskStatus.ASSIGNED)
                        .build()));

        List<CycleCountTaskResponse> tasks = service.getTasksForPlan(PLAN_ID);

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getPlanId()).isEqualTo(PLAN_ID);
        assertThat(tasks.get(0).getItemSku()).isEqualTo("OIL-5W30-5QT");
    }

    @Test
    void listingTasksForUnknownPlanThrowsNotFound() {
        when(planRepository.existsById(PLAN_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getTasksForPlan(PLAN_ID)).isInstanceOf(CycleCountPlanNotFoundException.class);
    }
}
