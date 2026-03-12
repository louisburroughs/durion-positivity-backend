package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.ClarificationResponseDTO;
import com.positivity.mcp.internal.dto.IntentV1;
import com.positivity.mcp.service.IntentParserService;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

@Service
public class IntentParserServiceImpl implements IntentParserService {

    @Override
    public IntentV1 parse(@NonNull String prompt, @NonNull UUID sessionId, @NonNull UUID correlationId) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public IntentV1 resolve(@NonNull UUID intentId, @NonNull ClarificationResponseDTO response) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
