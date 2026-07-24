package com.positivity.order.service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read model of a register session (parity stories G1/G2).
 *
 * @param sessionId session identifier
 * @param terminalId terminal the drawer belongs to
 * @param locationId shop location
 * @param openedByClerkId clerk who opened the session
 * @param status OPEN / CLOSING / CLOSED
 * @param openingFloat starting drawer cash
 * @param countedCash physical cash counted at begin-close (null until then)
 * @param theoreticalCash expected cash snapshotted at close (null until then)
 * @param overShort countedCash − theoreticalCash (null until closed)
 * @param varianceApproved whether an over/short beyond the limit was approved at close
 * @param closedByClerkId clerk who confirmed the close (null until closed)
 * @param openedAt when the session opened
 * @param closingStartedAt when begin-close recorded the count (null until then)
 * @param closedAt when the session reached CLOSED (null until then)
 */
public record RegisterSessionSummary(
        @NonNull UUID sessionId,
        @NonNull String terminalId,
        @Nullable UUID locationId,
        @NonNull String openedByClerkId,
        @NonNull String status,
        @NonNull BigDecimal openingFloat,
        @Nullable BigDecimal countedCash,
        @Nullable BigDecimal theoreticalCash,
        @Nullable BigDecimal overShort,
        boolean varianceApproved,
        @Nullable String closedByClerkId,
        @NonNull Instant openedAt,
        @Nullable Instant closingStartedAt,
        @Nullable Instant closedAt) {}
