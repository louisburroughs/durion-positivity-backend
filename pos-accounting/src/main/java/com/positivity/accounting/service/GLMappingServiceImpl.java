package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.GLMappingCreateRequest;
import com.positivity.accounting.internal.dto.GLMappingCreateResponse;
import com.positivity.accounting.internal.dto.GLMappingResolveRequest;
import com.positivity.accounting.internal.dto.GLMappingResolveResponse;
import com.positivity.accounting.internal.dto.GLMappingResponse;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.events.EmitEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of GL mapping service.
 * Manages creation and resolution of GL mappings with temporal validity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class GLMappingServiceImpl implements GLMappingService {

        private final GLMappingRepository glMappingRepository;
        private final GLAccountRepository glAccountRepository;

        @Override
        @EmitEvent(id = "ACCOUNTING_GL_MAPPING_CREATE", apiVersion = "1")
        public @NonNull GLMappingCreateResponse createMapping(@NonNull GLMappingCreateRequest request) {
                log.info("Creating GL mapping: sourceSystem={}, externalCode={}, glAccountId={}",
                                request.getSourceSystem(), request.getExternalCode(), request.getGlAccountId());

                // Validate GL account exists and is active
                GLAccount glAccount = glAccountRepository.findById(request.getGlAccountId())
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "GL account not found: " + request.getGlAccountId()));

                if (glAccount.getActivationDate() == null) {
                        throw new IllegalArgumentException(
                                        "GL account " + glAccount.getAccountCode() + " is not active");
                }

                // Check for overlapping mappings
                UUID excludeId = new UUID(0L, 0L); // No existing mapping to exclude
                var overlaps = glMappingRepository.findOverlappingMappings(
                                request.getSourceSystem(),
                                request.getExternalCode(),
                                request.getEffectiveStartDate(),
                                request.getEffectiveEndDate(),
                                excludeId);

                if (!overlaps.isEmpty()) {
                        throw new IllegalArgumentException(
                                        String.format("Mapping has overlapping effective dates with existing mapping(s) for %s:%s",
                                                        request.getSourceSystem(), request.getExternalCode()));
                }

                // Create mapping entity
                GLMapping mapping = new GLMapping();
                mapping.setSourceSystem(request.getSourceSystem());
                mapping.setExternalCode(request.getExternalCode());
                mapping.setGlAccountId(request.getGlAccountId());
                mapping.setEffectiveStartDate(request.getEffectiveStartDate());
                mapping.setEffectiveEndDate(request.getEffectiveEndDate());
                mapping.setDimensions(request.getDimensions());
                // postingCategoryId and mappingKeyId are null for source/external code mappings
                mapping.setCreatedBy(getCurrentUsername());

                GLMapping saved = glMappingRepository.save(mapping);

                log.info("Created GL mapping: id={}, sourceSystem={}, externalCode={}",
                                saved.getGlMappingId(), saved.getSourceSystem(), saved.getExternalCode());

                // Build response
                GLMappingResponse mappingResponse = GLMappingResponse.builder()
                                .glMappingId(saved.getGlMappingId())
                                .sourceSystem(saved.getSourceSystem())
                                .externalCode(saved.getExternalCode())
                                .glAccountId(saved.getGlAccountId())
                                .accountCode(glAccount.getAccountCode())
                                .accountName(glAccount.getAccountName())
                                .effectiveStartDate(saved.getEffectiveStartDate())
                                .effectiveEndDate(saved.getEffectiveEndDate())
                                .dimensions(saved.getDimensions())
                                .priority(0) // Default priority
                                .createdAt(saved.getCreatedAt())
                                .createdBy(saved.getCreatedBy())
                                .build();

                return GLMappingCreateResponse.builder()
                                .mapping(mappingResponse)
                                .build();
        }

        @Override
        @EmitEvent(id = "ACCOUNTING_GL_MAPPING_RESOLVE", apiVersion = "1")
        public @NonNull GLMappingResolveResponse resolveMapping(@NonNull GLMappingResolveRequest request) {
                log.info("Resolving GL mapping: sourceSystem={}, externalCode={}, transactionDate={}",
                                request.getSourceSystem(), request.getExternalCode(), request.getTransactionDate());

                // Find effective mapping at transaction date
                Optional<GLMapping> mapping = glMappingRepository.findEffectiveMapping(
                                request.getSourceSystem(),
                                request.getExternalCode(),
                                request.getTransactionDate());

                if (mapping.isEmpty()) {
                        throw new IllegalArgumentException(
                                        String.format("No GL mapping found for %s:%s at %s",
                                                        request.getSourceSystem(),
                                                        request.getExternalCode(),
                                                        request.getTransactionDate()));
                }

                UUID glAccountId = mapping.get().getGlAccountId();

                log.info("Resolved GL mapping: glAccountId={}", glAccountId);

                return GLMappingResolveResponse.builder()
                                .glAccountId(glAccountId)
                                .build();
        }

        /**
         * Get the current username from security context.
         * Falls back to "system" if no authentication present.
         */
        private String getCurrentUsername() {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                return (auth != null && auth.getName() != null) ? auth.getName() : "system";
        }
}
