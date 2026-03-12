package com.positivity.mcp.service;

import com.positivity.mcp.internal.dto.ClarificationResponseDTO;
import com.positivity.mcp.internal.dto.IntentV1;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface IntentParserService {
    @NonNull
    IntentV1 parse(@NonNull String prompt, @NonNull UUID sessionId, @NonNull UUID correlationId);

    @NonNull
    IntentV1 resolve(@NonNull UUID intentId, @NonNull ClarificationResponseDTO response);
}
