package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.EvalTurnTrace;
import com.positivity.mcp.internal.repository.EvalTurnTraceRepository;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Reads recorded eval turn traces for one caller (#1706 ask 2).
 *
 * <p>The caller filter lives here rather than in the controller because it is a rule about who may
 * see what, not a transport concern — and because ArchUnit correctly refuses to let a controller
 * reach a repository directly.
 *
 * <p>The filter is applied in Java rather than SQL: V45 stores each trace as a single JSON payload
 * with no username column, so a WHERE clause would mean parsing JSON in the query. Trace volume is
 * bounded by the retention window, which makes reading a time slice the cheaper trade than a
 * migration.
 */
@Service
@ConditionalOnProperty(name = "mcp.eval.turn-trace.enabled", havingValue = "true")
public class EvalTurnTraceQueryService {

    /** Read ceiling before the caller filter, so one busy actor cannot crowd out another. */
    static final int SCAN_LIMIT = 500;

    static final int MAX_RESULTS = 200;

    private final EvalTurnTraceRepository repository;

    public EvalTurnTraceQueryService(@NonNull EvalTurnTraceRepository repository) {
        this.repository = repository;
    }

    /**
     * The traces belonging to {@code username} recorded at or after {@code since}, newest first.
     *
     * <p>{@code limit} is applied AFTER the caller filter. Limiting first would let another actor's
     * traces consume the caller's quota, so a busy shared window could return none of the caller's
     * own while still reporting success — a silent empty result, which is the shape of failure
     * #1706 exists to remove.
     */
    public @NonNull List<EvalTurnTrace> findForCaller(@NonNull String username, @NonNull Instant since, int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_RESULTS));
        return repository.findRecorded(since, SCAN_LIMIT).stream()
                .filter(trace -> username.equals(trace.username()))
                .limit(capped)
                .toList();
    }
}
