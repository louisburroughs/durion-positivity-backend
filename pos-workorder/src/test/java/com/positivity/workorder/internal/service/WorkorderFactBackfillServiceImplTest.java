package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContextMissingException;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.testing.TenantTestSupport;
import com.positivity.workorder.internal.service.WorkorderFactBackfillService.BackfillResult;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link WorkorderFactBackfillServiceImpl} (issue #2021 AC8).
 *
 * <p>The paging walk decides whether a stale workorder is reachable at all, and the tenant fan-out
 * decides whose rows a platform operator's run actually covers, so these cover termination, the
 * resume cursor, the per-run bound, and both tenant paths.
 */
class WorkorderFactBackfillServiceImplTest {

    private static final UUID TENANT_A = TenantTestSupport.TENANT_A;
    private static final UUID TENANT_B = TenantTestSupport.TENANT_B;

    private final WorkorderFactBackfillPagePublisher pagePublisher = mock(WorkorderFactBackfillPagePublisher.class);

    private WorkorderFactBackfillServiceImpl service;

    /** Ids that sort in creation order, so the cursor assertions are meaningful. */
    private static List<UUID> ids(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> UUID.fromString("00000000-0000-0000-0000-%012d".formatted(i + 1)))
                .toList();
    }

    private void useRegistry(List<UUID> tenants) {
        service = new WorkorderFactBackfillServiceImpl(pagePublisher, new TenantIterator(() -> tenants));
        ReflectionTestUtils.setField(service, "pageSize", 2);
        ReflectionTestUtils.setField(service, "maxRowsPerRun", 100);
    }

    @BeforeEach
    void setUp() {
        useRegistry(List.of(TENANT_A, TENANT_B));
    }

    // ---------------------------------------------------------------------------------------
    // Single-tenant paging walk
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("#2021 walks every page until a short page ends the run, and reports the last id")
    void walksAllPagesAndReportsCursor() {
        List<UUID> ids = ids(3);
        when(pagePublisher.publishPage(null, 2)).thenReturn(List.of(ids.get(0), ids.get(1)));
        when(pagePublisher.publishPage(ids.get(1), 2)).thenReturn(List.of(ids.get(2)));

        BackfillResult result = TenantTestSupport.asTenant(TENANT_A, () -> service.backfillForCaller(null));

        assertThat(result.published()).isEqualTo(3);
        assertThat(result.lastId()).isEqualTo(ids.get(2));
        assertThat(result.more()).isFalse();
        verify(pagePublisher).publishPage(null, 2);
        verify(pagePublisher).publishPage(ids.get(1), 2);
        verifyNoMoreInteractions(pagePublisher);
    }

    @Test
    @DisplayName("#2021 each page resumes from the previous page's last id, never an offset")
    void pagesByKeysetCursor() {
        List<UUID> ids = ids(2);
        when(pagePublisher.publishPage(null, 2)).thenReturn(List.of(ids.get(0), ids.get(1)));
        when(pagePublisher.publishPage(ids.get(1), 2)).thenReturn(List.of());

        TenantTestSupport.asTenant(TENANT_A, () -> service.backfillForCaller(null));

        verify(pagePublisher).publishPage(ids.get(1), 2);
    }

    @Test
    @DisplayName("#2021 an empty selection is a no-op run, not an error")
    void emptySelectionIsNoOp() {
        when(pagePublisher.publishPage(any(), eq(2))).thenReturn(List.of());

        BackfillResult result = TenantTestSupport.asTenant(TENANT_A, () -> service.backfillForCaller(null));

        assertThat(result.published()).isZero();
        assertThat(result.more()).isFalse();
    }

    @Test
    @DisplayName("#2021 a run stops at the per-run bound and reports more work remaining")
    void stopsAtPerRunBound() {
        ReflectionTestUtils.setField(service, "maxRowsPerRun", 2);
        List<UUID> ids = ids(2);
        when(pagePublisher.publishPage(null, 2)).thenReturn(List.of(ids.get(0), ids.get(1)));

        BackfillResult result = TenantTestSupport.asTenant(TENANT_A, () -> service.backfillForCaller(null));

        assertThat(result.published()).isEqualTo(2);
        assertThat(result.more()).isTrue();
        assertThat(result.lastId()).isEqualTo(ids.get(1));
        verify(pagePublisher).publishPage(null, 2);
        verifyNoMoreInteractions(pagePublisher);
    }

    @Test
    @DisplayName("#2021 a caller-supplied cursor resumes rather than restarting the walk")
    void resumesFromSuppliedCursor() {
        List<UUID> ids = ids(2);
        when(pagePublisher.publishPage(ids.get(0), 2)).thenReturn(List.of());

        BackfillResult result = TenantTestSupport.asTenant(TENANT_A, () -> service.backfillForCaller(ids.get(0)));

        assertThat(result.published()).isZero();
        verify(pagePublisher).publishPage(ids.get(0), 2);
    }

    @Test
    @DisplayName("#2021 a non-positive page size fails at startup, not when an operator runs a backfill")
    void rejectsMisconfiguredBounds() {
        ReflectionTestUtils.setField(service, "pageSize", 0);

        assertThatThrownBy(service::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("page-size");

        ReflectionTestUtils.setField(service, "pageSize", 500);
        ReflectionTestUtils.setField(service, "maxRowsPerRun", 0);
        assertThatThrownBy(service::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-rows-per-run");
    }

    @Test
    @DisplayName("#2021 refuses to run with no tenant bound rather than guessing whose rows to backfill")
    void unboundCallerFailsClosed() {
        assertThatThrownBy(() -> service.backfillForCaller(null)).isInstanceOf(TenantContextMissingException.class);
        verifyNoMoreInteractions(pagePublisher);
    }

    // ---------------------------------------------------------------------------------------
    // Platform-tenant fan-out (ADR-0062 §3, mirrors OutboxReplayServiceImpl#replaySinceForCaller)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("#2021 a platform-tenant caller fans out over every active tenant, each from the beginning")
    void platformOperatorFansOutOverActiveTenants() {
        List<UUID> tenantAIds = ids(1);
        UUID tenantBId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        // Each tenant's page is short (1 row for a page size of 2), so its walk stops after one page.
        when(pagePublisher.publishPage(null, 2))
                .thenReturn(List.of(tenantAIds.get(0)))
                .thenReturn(List.of(tenantBId));

        BackfillResult result = TenantTestSupport.asTenant(PlatformTenant.ID, () -> service.backfillForCaller(null));

        // One tenant's worth of published rows from each of the two tenants in the registry.
        assertThat(result.published()).isEqualTo(2);
        assertThat(result.more()).isFalse();
        // A cursor cannot span tenants -- the aggregate result never names a per-tenant last id.
        assertThat(result.lastId()).isNull();
    }

    @Test
    @DisplayName("#2021 a platform operator's afterId is ignored, not honored for an arbitrary tenant")
    void platformOperatorIgnoresSuppliedCursor() {
        when(pagePublisher.publishPage(eq(null), eq(2))).thenReturn(List.of());

        UUID suppliedCursor = UUID.randomUUID();
        BackfillResult result =
                TenantTestSupport.asTenant(PlatformTenant.ID, () -> service.backfillForCaller(suppliedCursor));

        assertThat(result.published()).isZero();
        // Every tenant's own walk still starts at the beginning -- the supplied cursor is never
        // passed through to publishPage.
        verify(pagePublisher, org.mockito.Mockito.times(2)).publishPage(null, 2);
    }

    @Test
    @DisplayName("#2021 a run that reaches its bound for any tenant reports more work remaining")
    void platformFanOutReportsMoreWhenAnyTenantHitsItsBound() {
        ReflectionTestUtils.setField(service, "maxRowsPerRun", 1);
        List<UUID> ids = ids(1);
        when(pagePublisher.publishPage(null, 1)).thenReturn(List.of(ids.get(0)));

        BackfillResult result = TenantTestSupport.asTenant(PlatformTenant.ID, () -> service.backfillForCaller(null));

        assertThat(result.published()).isEqualTo(2);
        assertThat(result.more()).isTrue();
    }

    @Test
    @DisplayName("#2021 an empty tenant registry visits nobody rather than throwing")
    void emptyRegistryIsANoOp() {
        useRegistry(List.of());

        BackfillResult result = TenantTestSupport.asTenant(PlatformTenant.ID, () -> service.backfillForCaller(null));

        assertThat(result.published()).isZero();
        assertThat(result.more()).isFalse();
        verifyNoMoreInteractions(pagePublisher);
    }
}
