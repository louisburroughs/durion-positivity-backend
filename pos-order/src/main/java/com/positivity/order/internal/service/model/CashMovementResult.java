package com.positivity.order.internal.service.model;

import com.positivity.order.internal.dto.CashMovementSummary;
import org.jspecify.annotations.NonNull;

/**
 * A recorded cash movement and whether this request recorded it or replayed the first result of the
 * same {@code requestId} (CAP:550 S16, #2512; §8.2): 201 for a new movement, 200 for a replay.
 */
public record CashMovementResult(@NonNull CashMovementSummary movement, boolean replayed) {}
