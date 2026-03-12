package com.positivity.mcp.service;

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
}
