package com.positivity.price.internal.service;

import com.positivity.price.internal.dto.CreateRestrictionRuleRequest;
import com.positivity.price.internal.dto.RestrictionRuleResponse;
import com.positivity.price.internal.entity.RestrictionRule;
import com.positivity.price.internal.exception.RestrictionRuleNotFoundException;
import com.positivity.price.internal.repository.RestrictionRuleRepository;
import com.positivity.price.service.RestrictionRuleService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RestrictionRuleServiceImpl implements RestrictionRuleService {

    private final RestrictionRuleRepository repository;

    public RestrictionRuleServiceImpl(RestrictionRuleRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public @NonNull RestrictionRuleResponse createRule(@NonNull CreateRestrictionRuleRequest request) {
        RestrictionRule entity = repository.save(toEntity(request));
        return toResponse(entity);
    }

    @Override
    public @NonNull RestrictionRuleResponse getRuleById(@NonNull UUID ruleId) {
        RestrictionRule entity = repository.findById(ruleId)
                .orElseThrow(() -> new RestrictionRuleNotFoundException("Rule not found: " + ruleId));
        return toResponse(entity);
    }

    @Override
    public @NonNull List<RestrictionRuleResponse> listRules() {
        return repository.findByActiveTrue().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public @NonNull RestrictionRuleResponse deactivateRule(@NonNull UUID ruleId) {
        RestrictionRule entity = repository.findById(ruleId)
                .orElseThrow(() -> new RestrictionRuleNotFoundException("Rule not found: " + ruleId));
        if (!entity.isActive()) {
            throw new IllegalStateException("Rule already inactive: " + ruleId);
        }
        entity.setEffectiveTo(LocalDate.now());
        entity.setActive(false);
        return toResponse(repository.save(entity));
    }

    private RestrictionRule toEntity(CreateRestrictionRuleRequest request) {
        return RestrictionRule.builder()
                .productId(request.productId())
                .locationTag(request.locationTag())
                .serviceTag(request.serviceTag())
                .effectiveFrom(request.effectiveFrom())
                .effectiveTo(request.effectiveTo())
                .active(true)
                .policyVersion(request.policyVersion() == null ? 1 : request.policyVersion())
                .overrideable(request.overrideable())
                .build();
    }

    private RestrictionRuleResponse toResponse(RestrictionRule entity) {
        return new RestrictionRuleResponse(
                entity.getRuleId(),
                entity.getProductId(),
                entity.getLocationTag(),
                entity.getServiceTag(),
                entity.isActive(),
                entity.getEffectiveFrom(),
                entity.getEffectiveTo());
    }
}