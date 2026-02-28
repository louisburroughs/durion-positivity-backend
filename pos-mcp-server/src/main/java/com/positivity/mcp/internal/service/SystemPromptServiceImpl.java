package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.SystemPromptRequest;
import com.positivity.mcp.internal.dto.SystemPromptResponse;
import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import com.positivity.mcp.service.SystemPromptService;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class SystemPromptServiceImpl implements SystemPromptService {

    private final SystemPromptRepository repository;

    public SystemPromptServiceImpl(@NonNull SystemPromptRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public @NonNull SystemPromptResponse create(@NonNull SystemPromptRequest request) {
        if (repository.existsByName(request.name())) {
            throw new IllegalArgumentException("Prompt with name already exists: " + request.name());
        }
        var prompt = new SystemPrompt();
        prompt.setName(request.name());
        prompt.setContent(request.content());
        return SystemPromptResponse.from(repository.save(prompt));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<SystemPromptResponse> findAll() {
        return repository.findAll().stream().map(SystemPromptResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull SystemPromptResponse get(@NonNull UUID id) {
        return repository.findById(id)
                .map(SystemPromptResponse::from)
                .orElseThrow(() -> new NoSuchElementException("Prompt not found: " + id));
    }

    @Override
    @Transactional
    public @NonNull SystemPromptResponse update(@NonNull UUID id, @NonNull SystemPromptRequest request) {
        var prompt = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Prompt not found: " + id));
        if (!prompt.getName().equals(request.name()) && repository.existsByName(request.name())) {
            throw new IllegalArgumentException("Prompt with name already exists: " + request.name());
        }
        prompt.setName(request.name());
        prompt.setContent(request.content());
        return SystemPromptResponse.from(repository.save(prompt));
    }

    @Override
    @Transactional
    public void delete(@NonNull UUID id) {
        repository.deleteById(id);
    }
}
