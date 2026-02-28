package com.positivity.mcp.service;

import java.util.List;
import java.util.UUID;

import com.positivity.mcp.internal.dto.SystemPromptRequest;
import com.positivity.mcp.internal.dto.SystemPromptResponse;

public interface SystemPromptService {

    SystemPromptResponse create(SystemPromptRequest request);

    List<SystemPromptResponse> findAll();

    SystemPromptResponse get(UUID id);

    SystemPromptResponse update(UUID id, SystemPromptRequest request);

    void delete(UUID id);

}