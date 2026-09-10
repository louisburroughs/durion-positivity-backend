package com.positivity.mcp.internal.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.positivity.mcp.internal.repository.EvalTurnTraceRepository;
import com.positivity.tenancy.StaticTenantRegistry;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AlphaEvalTraceRetentionSchedulerTest {

    @Test
    void cleanupDeletesEveryTraceExpiredAtTheCurrentClockInstant() {
        Instant now = Instant.parse("2026-09-04T20:00:00Z");
        EvalTurnTraceRepository repository = mock(EvalTurnTraceRepository.class);
        AlphaEvalTraceRetentionScheduler scheduler =
                new AlphaEvalTraceRetentionScheduler(repository, Clock.fixed(now, ZoneOffset.UTC), singleTenant());

        scheduler.deleteExpiredTraces();

        verify(repository).deleteExpired(now);
    }

    /** One active tenant, the alpha default, for the per-tenant sweep (ADR-0062). */
    private static TenantIterator singleTenant() {
        TenancyProperties tenancy = new TenancyProperties();
        tenancy.setDefaultTenantId(TenantTestSupport.TENANT_A);
        return new TenantIterator(new StaticTenantRegistry(tenancy));
    }
}
