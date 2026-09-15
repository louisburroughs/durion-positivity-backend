package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.positivity.tenancy.StaticTenantRegistry;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.testing.TenantTestSupport;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The startup migration for #2011: workorders left {@code ASSIGNED} by the old rule are handed to
 * the state machine's reconciliation, one per stranded row, per tenant.
 *
 * <p>Two properties matter beyond "it runs". It must go through {@code reconcileAssigned} rather
 * than transition rows outright — the query's answer is stale the moment it is read, so a row that
 * has since been completed or moved on must be left to the reconciliation to judge under its lock.
 * And a tenant whose pass fails must not stop the ones after it, or take the service's startup with
 * it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AssignedInvariantMigrationService")
class AssignedInvariantMigrationServiceTest {

    private static final UUID STRANDED_ONE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4001");
    private static final UUID STRANDED_TWO = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4002");
    private static final String ACTOR = "system";

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private WorkorderStateMachine stateMachine;

    private AssignedInvariantMigrationService migration;

    @BeforeEach
    void createService() {
        // One active tenant, the alpha default, for the per-tenant sweep (ADR-0062); the transaction
        // manager is a mock, so the TransactionTemplate just runs the body.
        TenancyProperties tenancy = new TenancyProperties();
        tenancy.setDefaultTenantId(TenantTestSupport.TENANT_A);
        migration = new AssignedInvariantMigrationService(
                workorderRepository,
                stateMachine,
                new TenantIterator(new StaticTenantRegistry(tenancy)),
                mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("every stranded workorder is handed to the reconciliation, with the migration's reason")
    void strandedRowsAreReconciled() {
        when(workorderRepository.findAssignedWithoutTechnicianAndPosition())
                .thenReturn(List.of(STRANDED_ONE, STRANDED_TWO));

        migration.migrateForTenant();

        verify(stateMachine)
                .reconcileAssigned(eq(STRANDED_ONE), eq(ACTOR), eq("ASSIGNED requires technician and bay/mobile unit"));
        verify(stateMachine)
                .reconcileAssigned(eq(STRANDED_TWO), eq(ACTOR), eq("ASSIGNED requires technician and bay/mobile unit"));
        verifyNoMoreInteractions(stateMachine);
    }

    /**
     * The rows are reconciled, never transitioned outright: on a rolling deploy another instance can
     * fill the missing half between the query and the loop, and an unconditional demotion would walk
     * a workorder that has just become genuinely ASSIGNED back to APPROVED.
     */
    @Test
    @DisplayName("no workorder is transitioned outright")
    void nothingIsTransitionedDirectly() {
        when(workorderRepository.findAssignedWithoutTechnicianAndPosition()).thenReturn(List.of(STRANDED_ONE));

        migration.migrateForTenant();

        verify(stateMachine, never()).transitionWorkorder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a tenant with nothing stranded writes nothing")
    void nothingStrandedIsANoOp() {
        when(workorderRepository.findAssignedWithoutTechnicianAndPosition()).thenReturn(List.of());

        migration.migrateForTenant();

        verifyNoMoreInteractions(stateMachine);
    }

    /**
     * A migration that cannot finish must not stop the service from starting: what it did not correct
     * it finds again on the next boot, which is cheaper than a service that will not come up.
     */
    @Test
    @DisplayName("a failing tenant is isolated and startup still completes")
    void aFailingTenantDoesNotStopStartup() {
        when(workorderRepository.findAssignedWithoutTechnicianAndPosition()).thenReturn(List.of(STRANDED_ONE));
        doThrow(new IllegalStateException("boom")).when(stateMachine).reconcileAssigned(eq(STRANDED_ONE), any(), any());

        assertThatCode(() -> migration.run(null)).doesNotThrowAnyException();
    }
}
