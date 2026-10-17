package com.positivity.tenant.internal.service;

import com.positivity.tenant.internal.dto.MonthlyAllowanceDto;
import com.positivity.tenant.internal.dto.ReadingTierCreateRequest;
import com.positivity.tenant.internal.dto.ReadingTierResponse;
import com.positivity.tenant.internal.dto.ReadingTierUpdateRequest;
import com.positivity.tenant.internal.entity.ReadingTierEntity;
import com.positivity.tenant.internal.entity.TenantEntity;
import com.positivity.tenant.internal.enums.TenantStatus;
import com.positivity.tenant.internal.exception.ReadingTierExistsException;
import com.positivity.tenant.internal.exception.ReadingTierInUseException;
import com.positivity.tenant.internal.exception.ReadingTierNotAssignableException;
import com.positivity.tenant.internal.exception.ResourceNotFoundException;
import com.positivity.tenant.internal.repository.ReadingTierRepository;
import com.positivity.tenant.internal.repository.TenantRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReadingTierServiceImpl implements ReadingTierService {

    private static final String CODE_CONSTRAINT = "uq_reading_tier_code";

    private final ReadingTierRepository tiers;
    private final TenantRepository tenants;
    private final TenantFactPublisher factPublisher;

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ReadingTierResponse> list() {
        return tiers.findAllByOrderByCodeAsc().stream()
                .map(ReadingTierServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public @NonNull ReadingTierResponse create(@NonNull ReadingTierCreateRequest request) {
        if (tiers.existsByCode(request.getCode())) {
            throw new ReadingTierExistsException("Reading tier already exists: " + request.getCode());
        }
        ReadingTierEntity tier = ReadingTierEntity.builder()
                .code(request.getCode())
                .displayName(request.getDisplayName().trim())
                .monthlyAllowanceAmount(request.getMonthlyAllowance().getAmount())
                .monthlyAllowanceCurrency(request.getMonthlyAllowance().getCurrencyCode())
                .active(true)
                .build();
        ReadingTierEntity saved;
        try {
            saved = tiers.saveAndFlush(tier);
        } catch (DataIntegrityViolationException e) {
            if (TenantServiceImpl.isConstraintViolation(e, CODE_CONSTRAINT)) {
                throw new ReadingTierExistsException("Reading tier already exists: " + request.getCode());
            }
            throw e;
        }
        log.info(
                "Created reading tier code={} allowance={} {}",
                saved.getCode(),
                saved.getMonthlyAllowanceAmount(),
                saved.getMonthlyAllowanceCurrency());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull ReadingTierResponse update(@NonNull String code, @NonNull ReadingTierUpdateRequest request) {
        ReadingTierEntity tier = load(code);
        boolean changed = false;
        if (request.getDisplayName() != null) {
            String displayName = request.getDisplayName().trim();
            if (!displayName.equals(tier.getDisplayName())) {
                tier.setDisplayName(displayName);
                changed = true;
            }
        }
        boolean allowanceChanged = false;
        if (request.getMonthlyAllowance() != null) {
            MonthlyAllowanceDto allowance = request.getMonthlyAllowance();
            if (allowance.getAmount().compareTo(tier.getMonthlyAllowanceAmount()) != 0
                    || !allowance.getCurrencyCode().equals(tier.getMonthlyAllowanceCurrency())) {
                tier.setMonthlyAllowanceAmount(allowance.getAmount());
                tier.setMonthlyAllowanceCurrency(allowance.getCurrencyCode());
                allowanceChanged = true;
                changed = true;
            }
        }
        if (request.getActive() != null && request.getActive() != tier.isActive()) {
            if (!request.getActive() && ReadingTierEntity.STANDARD.equals(tier.getCode())) {
                throw new ReadingTierInUseException("Reading tier STANDARD is the default every new tenant starts on"
                        + " and stays active; reprice it instead");
            }
            if (!request.getActive()
                    && tenants.existsByReadingTierCodeAndStatusNot(tier.getCode(), TenantStatus.DECOMMISSIONED)) {
                throw new ReadingTierInUseException("Reading tier " + tier.getCode()
                        + " is assigned to a tenant that is not decommissioned; move those tenants first");
            }
            tier.setActive(request.getActive());
            changed = true;
        }
        if (!changed) {
            return toResponse(tier);
        }
        ReadingTierEntity saved = tiers.saveAndFlush(tier);
        if (allowanceChanged) {
            // One fact per tenant on the tier, in this transaction: each tenant's allowance is its own (AW63).
            List<TenantEntity> onTier = tenants.findByReadingTierCodeAndStatusNotOrderByCreatedAtAsc(
                    saved.getCode(), TenantStatus.DECOMMISSIONED);
            onTier.forEach(tenant -> factPublisher.readingAllowanceChanged(tenant, saved));
            log.info(
                    "Reading tier {} allowance changed to {} {}; {} tenant fact(s) queued",
                    saved.getCode(),
                    saved.getMonthlyAllowanceAmount(),
                    saved.getMonthlyAllowanceCurrency(),
                    onTier.size());
        }
        return toResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull ReadingTierEntity requireAssignable(@Nullable String requestedCode) {
        String code = requestedCode == null || requestedCode.isBlank() ? ReadingTierEntity.STANDARD : requestedCode;
        // Locked for the caller's transaction: the assignment and a reprice of the same tier serialise.
        ReadingTierEntity tier = tiers.lockByCode(code)
                .orElseThrow(() -> new ReadingTierNotAssignableException("Reading tier does not exist: " + code));
        if (!tier.isActive()) {
            throw new ReadingTierNotAssignableException("Reading tier is inactive: " + code);
        }
        return tier;
    }

    private ReadingTierEntity load(String code) {
        return tiers.lockByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Reading tier not found: " + code));
    }

    static ReadingTierResponse toResponse(ReadingTierEntity tier) {
        BigDecimal amount = tier.getMonthlyAllowanceAmount();
        return ReadingTierResponse.builder()
                .id(tier.getId())
                .code(tier.getCode())
                .displayName(tier.getDisplayName())
                .monthlyAllowance(new MonthlyAllowanceDto(amount, tier.getMonthlyAllowanceCurrency()))
                .active(tier.isActive())
                .version(tier.getVersion())
                .createdAt(tier.getCreatedAt())
                .updatedAt(tier.getUpdatedAt())
                .build();
    }
}
