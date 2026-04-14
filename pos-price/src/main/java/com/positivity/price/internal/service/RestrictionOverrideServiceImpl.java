package com.positivity.price.internal.service;

import com.positivity.price.internal.dto.RestrictionOverrideRequest;
import com.positivity.price.internal.dto.RestrictionOverrideResponse;
import com.positivity.price.internal.entity.RestrictionOverrideAudit;
import com.positivity.price.internal.entity.RestrictionRule;
import com.positivity.price.internal.enums.OverrideStatus;
import com.positivity.price.internal.exception.RestrictionRuleNotFoundException;
import com.positivity.price.internal.repository.RestrictionOverrideAuditRepository;
import com.positivity.price.internal.repository.RestrictionRuleRepository;
import com.positivity.price.service.RestrictionOverrideService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RestrictionOverrideServiceImpl implements RestrictionOverrideService {

    private final RestrictionOverrideAuditRepository auditRepository;
    private final RestrictionRuleRepository ruleRepository;

    public RestrictionOverrideServiceImpl(
            RestrictionOverrideAuditRepository auditRepository, RestrictionRuleRepository ruleRepository) {
        this.auditRepository = auditRepository;
        this.ruleRepository = ruleRepository;
    }

    @Override
    public @NonNull RestrictionOverrideResponse createOverride(@NonNull RestrictionOverrideRequest request) {
        Instant issuedAt = Instant.now();
        RestrictionRule rule = ruleRepository
                .findById(request.ruleId())
                .orElseThrow(() -> new RestrictionRuleNotFoundException("Rule not found: " + request.ruleId()));
        RestrictionOverrideAudit audit = RestrictionOverrideAudit.builder()
                .actor(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .ruleId(request.ruleId())
                .transactionId(request.transactionId())
                .productId(request.productId())
                .overrideContext(request.overrideContext())
                .reasonCode(request.reasonCode())
                .notes(request.notes())
                .approvedBy(request.approvedBy())
                .policyVersion(rule.getPolicyVersion())
                .status(OverrideStatus.ISSUED)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(24, ChronoUnit.HOURS))
                .build();
        audit = auditRepository.save(audit);
        return new RestrictionOverrideResponse(audit.getOverrideId(), audit.getExpiresAt());
    }
}
