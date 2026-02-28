package com.positivity.mcp.service;

import java.util.List;
import java.util.UUID;

import com.positivity.mcp.internal.dto.LlmApiConfigRequest;
import com.positivity.mcp.internal.dto.LlmApiConfigResponse;

public interface LlmApiConfigService {

    List<LlmApiConfigResponse> list();

    LlmApiConfigResponse get(UUID id);

    LlmApiConfigResponse create(LlmApiConfigRequest request);

    LlmApiConfigResponse update(UUID id, LlmApiConfigRequest request);

    void delete(UUID id);

}