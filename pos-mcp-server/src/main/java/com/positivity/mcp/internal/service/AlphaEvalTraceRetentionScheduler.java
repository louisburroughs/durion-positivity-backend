package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.repository.EvalTurnTraceRepository;
import com.positivity.tenancy.TenantIterator;
import java.time.Clock;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("alpha")
@ConditionalOnProperty(name = "mcp.eval.turn-trace.enabled", havingValue = "true")
public class AlphaEvalTraceRetentionScheduler {

    private final EvalTurnTraceRepository repository;
    private final Clock clock;
    private final TenantIterator tenantIterator;

    public AlphaEvalTraceRetentionScheduler(
            @NonNull EvalTurnTraceRepository repository, @NonNull Clock clock, @NonNull TenantIterator tenantIterator) {
        this.tenantIterator = tenantIterator;
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${mcp.eval.turn-trace.cleanup-interval:1h}")
    public void deleteExpiredTraces() {
        // Per tenant (ADR-0062 §3): the traces are tenant-scoped rows.
        tenantIterator.forEachActiveTenant(tenantId -> repository.deleteExpired(clock.instant()));
    }
}
