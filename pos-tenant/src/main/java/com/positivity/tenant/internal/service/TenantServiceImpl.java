package com.positivity.tenant.internal.service;

import com.positivity.tenant.internal.dto.TenantCreateRequest;
import com.positivity.tenant.internal.dto.TenantResponse;
import com.positivity.tenant.internal.dto.TenantUpdateRequest;
import com.positivity.tenant.internal.entity.TenantEntity;
import com.positivity.tenant.internal.enums.TenantStatus;
import com.positivity.tenant.internal.exception.DuplicateResourceException;
import com.positivity.tenant.internal.exception.InvalidStatusTransitionException;
import com.positivity.tenant.internal.exception.ResourceNotFoundException;
import com.positivity.tenant.internal.repository.AccountRepository;
import com.positivity.tenant.internal.repository.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant registry (ADR-0062 §7). Every mutation publishes its fact through the transactional
 * outbox inside the same transaction, so a fact exists if and only if the registry change committed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {

    static final String SLUG_CONSTRAINT = "tenant_slug_key";
    static final String DISPLAY_NAME_CONSTRAINT = "tenant_display_name_key";

    private final TenantRepository tenantRepository;
    private final AccountRepository accountRepository;
    private final TenantFactPublisher factPublisher;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull TenantResponse create(@NonNull TenantCreateRequest request) {
        if (!accountRepository.existsById(request.getAccountId())) {
            throw new ResourceNotFoundException("Account not found: " + request.getAccountId());
        }
        if (tenantRepository.existsBySlug(request.getSlug())) {
            throw new DuplicateResourceException("Tenant slug already taken: " + request.getSlug());
        }
        String displayName = resolveDisplayName(request);
        TenantEntity tenant = TenantEntity.builder()
                .slug(request.getSlug())
                .displayName(displayName)
                .displayNameKey(TenantDisplayNameAllocator.normalize(displayName))
                .status(TenantStatus.PENDING)
                .accountId(request.getAccountId())
                .cell(request.getCell())
                .initialAdminEmail(request.getInitialAdminEmail())
                .build();
        TenantEntity saved;
        try {
            saved = tenantRepository.saveAndFlush(tenant);
        } catch (DataIntegrityViolationException e) {
            if (isConstraintViolation(e, SLUG_CONSTRAINT)) {
                throw new DuplicateResourceException("Tenant slug already taken: " + request.getSlug());
            }
            if (isConstraintViolation(e, DISPLAY_NAME_CONSTRAINT)) {
                throw new DuplicateResourceException("Tenant display name already taken: " + displayName);
            }
            throw e;
        }
        factPublisher.tenantCreated(saved);
        log.info("Registered tenant id={} slug={} account={}", saved.getId(), saved.getSlug(), saved.getAccountId());
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull TenantResponse get(@NonNull UUID tenantId) {
        return toResponse(load(tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<TenantResponse> list(@Nullable TenantStatus status) {
        List<TenantEntity> tenants = status == null
                ? tenantRepository.findAllByOrderByCreatedAtAsc()
                : tenantRepository.findByStatusOrderByCreatedAtAsc(status);
        return tenants.stream().map(TenantServiceImpl::toResponse).toList();
    }

    @Override
    @Transactional
    public @NonNull TenantResponse update(@NonNull UUID tenantId, @NonNull TenantUpdateRequest request) {
        TenantEntity tenant = load(tenantId);
        if (tenant.getStatus().isTerminal()) {
            throw new InvalidStatusTransitionException(tenantId, tenant.getStatus(), tenant.getStatus());
        }
        boolean changed = false;
        if (request.getDisplayName() != null) {
            String displayName = TenantDisplayNameAllocator.displayForm(request.getDisplayName());
            if (displayName.isEmpty()) {
                // Bean validation rejects this at the edge; the guard keeps a whitespace-only name
                // from ever reaching the column, which would leave a tenant unfindable at login.
                throw new IllegalArgumentException("Tenant display name must not be blank");
            }
            if (!displayName.equals(tenant.getDisplayName())) {
                String key = TenantDisplayNameAllocator.normalize(displayName);
                if (!key.equals(tenant.getDisplayNameKey()) && tenantRepository.existsByDisplayNameKey(key)) {
                    throw new DuplicateResourceException("Tenant display name already taken: " + displayName);
                }
                tenant.setDisplayName(displayName);
                tenant.setDisplayNameKey(key);
                changed = true;
            }
        }
        if (request.getCell() != null && !request.getCell().equals(tenant.getCell())) {
            tenant.setCell(request.getCell());
            changed = true;
        }
        if (!changed) {
            return toResponse(tenant);
        }
        TenantEntity saved;
        try {
            saved = tenantRepository.saveAndFlush(tenant);
        } catch (DataIntegrityViolationException e) {
            if (isConstraintViolation(e, DISPLAY_NAME_CONSTRAINT)) {
                throw new DuplicateResourceException("Tenant display name already taken: " + tenant.getDisplayName());
            }
            throw e;
        }
        factPublisher.tenantUpdated(saved);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull TenantResponse suspend(@NonNull UUID tenantId) {
        TenantEntity tenant = transition(tenantId, TenantStatus.SUSPENDED);
        tenant.setSuspendedAt(Instant.now(clock));
        TenantEntity saved = tenantRepository.saveAndFlush(tenant);
        factPublisher.tenantSuspended(saved);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull TenantResponse reactivate(@NonNull UUID tenantId) {
        TenantEntity tenant = load(tenantId);
        if (tenant.getStatus() != TenantStatus.SUSPENDED) {
            // PENDING -> ACTIVE is provisioning's move, never an operator's.
            throw new InvalidStatusTransitionException(tenantId, tenant.getStatus(), TenantStatus.ACTIVE);
        }
        tenant.setStatus(TenantStatus.ACTIVE);
        TenantEntity saved = tenantRepository.saveAndFlush(tenant);
        factPublisher.tenantReactivated(saved);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull TenantResponse decommission(@NonNull UUID tenantId) {
        TenantEntity tenant = transition(tenantId, TenantStatus.DECOMMISSIONED);
        tenant.setDecommissionedAt(Instant.now(clock));
        TenantEntity saved = tenantRepository.saveAndFlush(tenant);
        factPublisher.tenantDecommissioned(saved);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void markProvisioned(@NonNull UUID tenantId) {
        TenantEntity tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            log.warn("tenant.provisioned for unknown tenant id={}; ignored", tenantId);
            return;
        }
        if (tenant.getStatus() != TenantStatus.PENDING) {
            log.debug("tenant.provisioned for tenant id={} in status {}; nothing to do", tenantId, tenant.getStatus());
            return;
        }
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setActivatedAt(Instant.now(clock));
        TenantEntity saved = tenantRepository.saveAndFlush(tenant);
        factPublisher.tenantUpdated(saved);
        log.info("Tenant id={} slug={} is ACTIVE", saved.getId(), saved.getSlug());
    }

    /**
     * True when the integrity failure names {@code constraint}. Only a named unique key is a
     * "already taken" conflict; any other integrity failure is a bug and must surface as such.
     */
    static boolean isConstraintViolation(DataIntegrityViolationException e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The requested display name, or one seeded from the owning account's legal name when the
     * request carries none (the registration is expected to name the tenant; this is the fallback).
     */
    private String resolveDisplayName(TenantCreateRequest request) {
        if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
            return TenantDisplayNameAllocator.displayForm(request.getDisplayName());
        }
        String legalName = accountRepository
                .findById(request.getAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.getAccountId()))
                .getLegalName();
        return TenantDisplayNameAllocator.allocate(legalName, tenantRepository::existsByDisplayNameKey);
    }

    private TenantEntity transition(UUID tenantId, TenantStatus target) {
        TenantEntity tenant = load(tenantId);
        if (!tenant.getStatus().canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(tenantId, tenant.getStatus(), target);
        }
        tenant.setStatus(target);
        return tenant;
    }

    private TenantEntity load(UUID tenantId) {
        return tenantRepository
                .findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
    }

    static TenantResponse toResponse(TenantEntity tenant) {
        return TenantResponse.builder()
                .id(tenant.getId())
                .slug(tenant.getSlug())
                .displayName(tenant.getDisplayName())
                .status(tenant.getStatus())
                .accountId(tenant.getAccountId())
                .cell(tenant.getCell())
                .initialAdminEmail(tenant.getInitialAdminEmail())
                .createdAt(tenant.getCreatedAt())
                .updatedAt(tenant.getUpdatedAt())
                .activatedAt(tenant.getActivatedAt())
                .suspendedAt(tenant.getSuspendedAt())
                .decommissionedAt(tenant.getDecommissionedAt())
                .build();
    }
}
