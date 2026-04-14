package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.LlmApiConfigRequest;
import com.positivity.mcp.internal.dto.LlmApiConfigResponse;
import com.positivity.mcp.internal.entity.LlmApiConfig;
import com.positivity.mcp.internal.repository.LlmApiConfigRepository;
import com.positivity.mcp.service.LlmApiConfigService;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LlmApiConfigServiceImpl implements LlmApiConfigService {

    private final LlmApiConfigRepository repository;

    public LlmApiConfigServiceImpl(@NonNull LlmApiConfigRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<LlmApiConfigResponse> list() {
        return repository.findAll().stream().map(LlmApiConfigResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull LlmApiConfigResponse get(@NonNull UUID id) {
        return repository
                .findById(id)
                .map(LlmApiConfigResponse::from)
                .orElseThrow(() -> new NoSuchElementException("LLM API config not found: " + id));
    }

    @Override
    @Transactional
    public @NonNull LlmApiConfigResponse create(@NonNull LlmApiConfigRequest request) {
        if (repository.existsByApiId(request.apiId())) {
            throw new IllegalArgumentException("LLM API id already exists: " + request.apiId());
        }
        var entity = new LlmApiConfig();
        apply(entity, request);
        return LlmApiConfigResponse.from(repository.save(entity));
    }

    @Override
    @Transactional
    public @NonNull LlmApiConfigResponse update(@NonNull UUID id, @NonNull LlmApiConfigRequest request) {
        var entity = repository
                .findById(id)
                .orElseThrow(() -> new NoSuchElementException("LLM API config not found: " + id));
        if (!entity.getApiId().equals(request.apiId()) && repository.existsByApiId(request.apiId())) {
            throw new IllegalArgumentException("LLM API id already exists: " + request.apiId());
        }
        apply(entity, request);
        return LlmApiConfigResponse.from(repository.save(entity));
    }

    @Override
    @Transactional
    public void delete(@NonNull UUID id) {
        repository.deleteById(id);
    }

    private void apply(LlmApiConfig entity, LlmApiConfigRequest request) {
        entity.setApiId(request.apiId());
        entity.setModel(request.model());
        entity.setBaseUrl(request.baseUrl());
        entity.setApiKey(request.apiKey());
        entity.setHeaders(request.headers() == null ? java.util.Map.of() : request.headers());
    }
}
