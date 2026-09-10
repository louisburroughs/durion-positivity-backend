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
        TenantEntity tenant = TenantEntity.builder()
                .slug(request.getSlug())
                .displayName(request.getDisplayName())
                .status(TenantStatus.PENDING)
                .accountId(request.getAccountId())
                .cell(request.getCell())
                .initialAdminEmail(request.getInitialAdminEmail())
                .build();
        TenantEntity saved;
        try {
            saved = tenantRepository.saveAndFlush(tenant);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateResourceException("Tenant slug already taken: " + request.getSlug());
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
        if (request.getDisplayName() != null && !request.getDisplayName().equals(tenant.getDisplayName())) {
            tenant.setDisplayName(request.getDisplayName());
            changed = true;
        }
        if (request.getCell() != null && !request.getCell().equals(tenant.getCell())) {
            tenant.setCell(request.getCell());
            changed = true;
        }
        if (!changed) {
            return toResponse(tenant);
        }
        TenantEntity saved = tenantRepository.saveAndFlush(tenant);
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
