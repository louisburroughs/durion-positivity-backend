package com.positivity.accounting.internal.service;

import java.time.Clock;
import java.time.Instant;

import com.positivity.accounting.internal.audit.entity.OverridePolicyThreshold;
import com.positivity.accounting.internal.audit.entity.PolicyValidationResult;
import com.positivity.accounting.internal.audit.repository.OverridePolicyThresholdRepository;
import com.positivity.accounting.service.PriceOverrideAuthorizationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Service for validating price override authorization.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PriceOverrideAuthorizationServiceImpl implements PriceOverrideAuthorizationService {

        private final Clock clock;
        private final OverridePolicyThresholdRepository policyRepository;

        // Forbidden override categories
        private static final List<String> FORBIDDEN_CATEGORIES = Arrays.asList(
                        "BELOW_COST",
                        "STACKING_VIOLATION",
                        "REGULATED_FEE",
                        "WARRANTY_OVERRIDE");

        /**
         * Validates if a price override is authorized for the given role.
         * 
         * @param role          the actor's role
         * @param originalPrice the original price
         * @param adjustedPrice the adjusted price
         * @param categoryCode  optional category code to check against forbidden list
         * @return validation result
         */
        @Override
        public AuthorizationResult validate(String role, BigDecimal originalPrice,
                        BigDecimal adjustedPrice, String categoryCode) {
                log.info("Validating price override for role {} from {} to {}",
                                role, originalPrice, adjustedPrice);

                // Check for forbidden category
                if (categoryCode != null && FORBIDDEN_CATEGORIES.contains(categoryCode)) {
                        log.warn("Price override rejected - forbidden category: {}", categoryCode);
                        String forbiddenMessage = "BELOW_COST".equals(categoryCode)
                                        ? "Pricing below cost not permitted"
                                        : "Override category not permitted: " + categoryCode;
                        return AuthorizationResult.builder()
                                        .result(PolicyValidationResult.REJECTED_FORBIDDEN)
                                        .forbiddenCategoryCode(categoryCode)
                                        .message(forbiddenMessage)
                                        .build();
                }

                // Get active policy for role
                Optional<OverridePolicyThreshold> policyOpt = policyRepository.findActiveByRoleAtTime(role,
                                Instant.now(clock));
                if (policyOpt.isEmpty()) {
                        log.error("No active policy found for role: {}", role);
                        return AuthorizationResult.builder()
                                        .result(PolicyValidationResult.REJECTED_THRESHOLD_EXCEEDED)
                                        .message("No authorization policy found for role: " + role)
                                        .build();
                }

                OverridePolicyThreshold policy = policyOpt.get();
                BigDecimal discountAmount = originalPrice.subtract(adjustedPrice);
                BigDecimal discountPercent = discountAmount
                                .divide(originalPrice, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100));

                // Check absolute amount threshold
                if (policy.getMaxAbsoluteAmount() != null &&
                                discountAmount.compareTo(policy.getMaxAbsoluteAmount()) > 0) {
                        log.warn("Price override rejected - exceeds max absolute amount: {} > {}",
                                        discountAmount, policy.getMaxAbsoluteAmount());
                        return AuthorizationResult.builder()
                                        .result(PolicyValidationResult.REJECTED_THRESHOLD_EXCEEDED)
                                        .message("Authorization threshold exceeded")
                                        .policyVersion(policy.getVersion())
                                        .build();
                }

                // Check percentage threshold
                if (policy.getMaxPercentOff() != null &&
                                discountPercent.compareTo(policy.getMaxPercentOff()) > 0) {
                        log.warn("Price override rejected - exceeds max percentage: {}% > {}%",
                                        discountPercent, policy.getMaxPercentOff());
                        return AuthorizationResult.builder()
                                        .result(PolicyValidationResult.REJECTED_THRESHOLD_EXCEEDED)
                                        .message("Authorization threshold exceeded")
                                        .policyVersion(policy.getVersion())
                                        .build();
                }

                log.info("Price override approved for role {} under policy version {}",
                                role, policy.getVersion());
                return AuthorizationResult.builder()
                                .result(PolicyValidationResult.APPROVED)
                                .policyVersion(policy.getVersion())
                                .authorizationLevel(role)
                                .build();
        }

        /**
         * Result of authorization validation.
         */
        @lombok.Data
        @lombok.Builder
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class AuthorizationResult {
                private PolicyValidationResult result;
                private String policyVersion;
                private String authorizationLevel;
                private String forbiddenCategoryCode;
                private String message;

                public boolean isApproved() {
                        return result == PolicyValidationResult.APPROVED;
                }
        }
}
