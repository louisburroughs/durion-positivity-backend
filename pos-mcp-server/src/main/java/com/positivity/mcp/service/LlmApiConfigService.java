package com.positivity.mcp.service;

import com.positivity.mcp.internal.dto.LlmApiConfigRequest;
import com.positivity.mcp.internal.dto.LlmApiConfigResponse;
import java.util.List;
import java.util.UUID;

public interface LlmApiConfigService {

    List<LlmApiConfigResponse> list();

    LlmApiConfigResponse get(UUID id);

    LlmApiConfigResponse create(LlmApiConfigRequest request);

    LlmApiConfigResponse update(UUID id, LlmApiConfigRequest request);

    void delete(UUID id);
}
