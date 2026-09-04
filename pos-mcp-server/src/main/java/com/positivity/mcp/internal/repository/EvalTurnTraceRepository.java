package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.EvalTurnTrace;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

public interface EvalTurnTraceRepository {

    void save(@NonNull EvalTurnTrace trace);

    void deleteExpired(@NonNull Instant expiresAtOrBefore);
}
