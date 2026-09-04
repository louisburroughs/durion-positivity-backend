package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.repository.EvalTurnTraceRepository;
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

    public AlphaEvalTraceRetentionScheduler(@NonNull EvalTurnTraceRepository repository, @NonNull Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${mcp.eval.turn-trace.cleanup-interval:1h}")
    public void deleteExpiredTraces() {
        repository.deleteExpired(clock.instant());
    }
}
