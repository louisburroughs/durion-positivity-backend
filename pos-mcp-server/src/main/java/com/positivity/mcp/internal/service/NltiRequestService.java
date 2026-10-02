package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface NltiRequestService {
    @NonNull
    NltiResponseV1 submit(@NonNull NltiRequestDTO request);

    @NonNull
    NltiResponseV1 submit(@NonNull NltiRequestDTO request, @Nullable UUID correlationId);

    /**
     * @param authHeader the caller's {@code Authorization} header, relayed on the reads a write
     *     plan's preview makes as the caller (#2374: the accounting event status check)
     */
    @NonNull
    NltiResponseV1 submit(@NonNull NltiRequestDTO request, @Nullable UUID correlationId, @Nullable String authHeader);
}
