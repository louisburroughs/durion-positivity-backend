package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.EvalTurnTrace;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;

public interface EvalTurnTraceRepository {

    void save(@NonNull EvalTurnTrace trace);

    void deleteExpired(@NonNull Instant expiresAtOrBefore);

    /**
     * Traces recorded at or after {@code since}, newest first, capped at {@code limit}.
     *
     * <p>Filtering by caller happens above this method rather than in SQL: V45 stores the trace as
     * a single JSON payload with no username column, so a WHERE clause would mean parsing JSON in
     * the query. Trace volume is small and bounded by the retention window, so reading a time slice
     * and filtering in Java is the cheaper trade than a migration.
     */
    @NonNull
    List<EvalTurnTrace> findRecorded(@NonNull Instant since, int limit);
}
