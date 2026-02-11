package com.positivity.accounting.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.accounting.internal.dto.DefaultGLMappingListResponse;
import com.positivity.accounting.internal.dto.DefaultGLMappingRequest;
import com.positivity.accounting.internal.dto.DefaultGLMappingResponse;
import com.positivity.accounting.internal.entity.DefaultGLMapping;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of DefaultGLMappingService.
 * Manages default GL account mappings for event types without explicit posting
 * rules.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DefaultGLMappingServiceImpl implements DefaultGLMappingService {

    private final DefaultGLMappingRepository repository;
    private final GLAccountRepository glAccountRepository;
    private final GLAccountService glAccountService;

    @Override
    @NonNull
    public DefaultGLMappingResponse createDefaultMapping(@NonNull DefaultGLMappingRequest request) {
        log.debug("Creating default GL mapping for eventType '{}'", request.getEventType());

        // Validate GL accounts exist and are active
        validateGLAccounts(request.getDebitAccountId(), request.getCreditAccountId());

        // Convert and save
        DefaultGLMapping mapping = DefaultGLMappingMapper.toEntity(request);
        DefaultGLMapping saved = repository.save(mapping);

        log.info("Created default GL mapping {} for eventType '{}'",
                saved.getMappingId(), saved.getEventType());

        return toResponse(saved);
    }

    @Override
    @NonNull
    public DefaultGLMappingResponse updateDefaultMapping(@NonNull UUID mappingId,
            @NonNull DefaultGLMappingRequest request) {
        log.debug("Updating default GL mapping {}", mappingId);

        DefaultGLMapping existing = repository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Default GL mapping not found: " + mappingId));

        // Validate GL accounts
        validateGLAccounts(request.getDebitAccountId(), request.getCreditAccountId());

        // Update entity
        DefaultGLMappingMapper.updateEntity(existing, request);
        DefaultGLMapping updated = repository.save(existing);

        log.info("Updated default GL mapping {}", mappingId);

        return toResponse(updated);
    }

    @Override
    public void deactivateDefaultMapping(@NonNull UUID mappingId) {
        log.debug("Deactivating default GL mapping {}", mappingId);

        DefaultGLMapping mapping = repository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Default GL mapping not found: " + mappingId));

        mapping.setActive(false);
        repository.save(mapping);

        log.info("Deactivated default GL mapping {}", mappingId);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public DefaultGLMappingResponse getDefaultMapping(@NonNull UUID mappingId) {
        DefaultGLMapping mapping = repository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Default GL mapping not found: " + mappingId));
        return toResponse(mapping);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public DefaultGLMappingListResponse listDefaultMappings(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("eventType"));
        Page<DefaultGLMapping> mappingsPage = repository.findAll(pageable);

        // Batch-load GL accounts to avoid N+1 query pattern
        List<DefaultGLMappingResponse> responses = toResponses(mappingsPage.getContent());

        return DefaultGLMappingListResponse.builder()
                .mappings(responses)
                .page(page)
                .size(size)
                .totalElements(mappingsPage.getTotalElements())
                .totalPages(mappingsPage.getTotalPages())
                .build();
    }

    @Override
    @Nullable
    @Transactional(readOnly = true)
    public DefaultGLMappingResponse findActiveDefaultForEvent(@NonNull String eventType,
            @Nullable UUID organizationId) {
        return repository.findActiveDefaultForEvent(eventType, organizationId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<DefaultGLMappingResponse> findByEventType(@NonNull String eventType) {
        return toResponses(repository.findByEventTypeAndActiveTrue(eventType));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<DefaultGLMappingResponse> findByOrganization(@NonNull UUID organizationId) {
        return toResponses(repository.findByOrganizationIdAndActiveTrue(organizationId));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<DefaultGLMappingResponse> findAllGlobalDefaults() {
        return toResponses(repository.findAllGlobalDefaults());
    }

    /**
     * Validates that the debit and credit GL accounts exist and are active for
     * posting.
     */
    private void validateGLAccounts(UUID debitAccountId, UUID creditAccountId) {
        LocalDateTime now = LocalDateTime.now();
        glAccountService.validateAccountForPosting(debitAccountId, now);
        glAccountService.validateAccountForPosting(creditAccountId, now);
    }

    /**
     * Converts entity to response DTO with account details.
     * For single mapping lookups (getDefaultMapping, findActiveDefaultForEvent).
     */
    private DefaultGLMappingResponse toResponse(DefaultGLMapping mapping) {
        GLAccount debitAccount = glAccountRepository.findById(mapping.getDebitAccountId()).orElse(null);
        GLAccount creditAccount = glAccountRepository.findById(mapping.getCreditAccountId()).orElse(null);
        return DefaultGLMappingMapper.toResponse(mapping, debitAccount, creditAccount);
    }

    /**
     * Batch converts multiple mappings to response DTOs.
     * Batch-loads all required GL accounts in a single query to avoid N+1 pattern.
     * <p>
     * For a page of 20 mappings, this reduces queries from 41 (1 + 2*20) to 2.
     */
    private List<DefaultGLMappingResponse> toResponses(List<DefaultGLMapping> mappings) {
        if (mappings.isEmpty()) {
            return List.of();
        }

        // Collect all distinct account IDs needed
        var accountIds = mappings.stream()
                .flatMap(m -> java.util.stream.Stream.of(m.getDebitAccountId(), m.getCreditAccountId()))
                .collect(Collectors.toSet());

        // Batch-load all accounts in one query
        var accountMap = glAccountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(GLAccount::getGlAccountId, acc -> acc));

        // Map each mapping using the pre-loaded account map
        return mappings.stream()
                .map(mapping -> DefaultGLMappingMapper.toResponse(
                        mapping,
                        accountMap.get(mapping.getDebitAccountId()),
                        accountMap.get(mapping.getCreditAccountId())))
                .collect(Collectors.toList());
    }
}
