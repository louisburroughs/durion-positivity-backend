package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.GetAccountTierResponse;
import com.positivity.customer.internal.dto.ResolveAccountTierRequest;
import com.positivity.customer.internal.dto.ResolveAccountTierResponse;
import com.positivity.customer.internal.entity.AbstractParty;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.enums.AccountTier;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Service for managing account tier assignments and resolution.
 * 
 * Provides operations to:
 * - Retrieve current account tier
 * - Compute/resolve tier based on business rules
 * - Apply tier assignments with audit tracking
 * 
 * Business rules for tier calculation:
 * - STANDARD: Default tier, < $50K annual revenue
 * - BRONZE: $50K - $100K annual revenue OR 3+ months old
 * - SILVER: $100K - $250K annual revenue OR 2+ contracts
 * - GOLD: $250K - $500K annual revenue OR 3+ contracts
 * - PLATINUM: $500K - $1M annual revenue OR 5+ contracts
 * - ENTERPRISE: $1M+ annual revenue OR custom arrangement
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AccountTierServiceImpl {

    private final CommercialPartyRepository commercialPartyRepository;

    // Tier calculation thresholds
    private static final BigDecimal BRONZE_THRESHOLD = new BigDecimal("50000");
    private static final BigDecimal SILVER_THRESHOLD = new BigDecimal("100000");
    private static final BigDecimal GOLD_THRESHOLD = new BigDecimal("250000");
    private static final BigDecimal PLATINUM_THRESHOLD = new BigDecimal("500000");
    private static final BigDecimal ENTERPRISE_THRESHOLD = new BigDecimal("1000000");

    private static final int BRONZE_AGE_MONTHS = 3;
    private static final int SILVER_CONTRACTS = 2;
    private static final int GOLD_CONTRACTS = 3;
    private static final int PLATINUM_CONTRACTS = 5;

    /**
     * Get the current tier for an account.
     * 
     * @param accountId the account/party identifier
     * @return tier information response
     * @throws IllegalArgumentException if account not found
     */
    @Transactional(readOnly = true)
    public GetAccountTierResponse getAccountTier(@NonNull UUID accountId) {
        log.debug("Getting tier for account: {}", accountId);

        CommercialParty party = commercialPartyRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        return buildTierResponse(party);
    }

    /**
     * Resolve/compute the appropriate tier for an account based on business rules.
     * 
     * Optionally applies the resolved tier to the account if requested.
     * 
     * @param request tier resolution request with calculation criteria
     * @return resolution result with recommended tier
     * @throws IllegalArgumentException if account not found
     */
    @Transactional
    public ResolveAccountTierResponse resolveAccountTier(@NonNull ResolveAccountTierRequest request) {
        log.info("Resolving tier for account: {} (apply={})", request.getAccountId(), request.isApplyTier());

        UUID partyId;
        try {
            partyId = UUID.fromString(request.getAccountId());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid account ID format: " + request.getAccountId(), e);
        }
        CommercialParty party = commercialPartyRepository.findById(partyId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + request.getAccountId()));

        AccountTier currentTier = party.getTier() != null ? party.getTier() : AccountTier.STANDARD;
        boolean manualOverride = party.isTierManualOverride();

        // Calculate recommended tier based on business rules
        TierCalculation calculation = calculateTier(request);
        AccountTier recommendedTier = calculation.tier;

        // Determine if tier should be applied
        boolean tierApplied = false;
        if (request.isApplyTier()) {
            if (!manualOverride || request.isForceRecalculation()) {
                applyTierToParty(party, recommendedTier, calculation.reason, request.isForceRecalculation());
                commercialPartyRepository.save(party);
                tierApplied = true;
                log.info("Applied tier {} to account {}", recommendedTier, request.getAccountId());
            } else {
                log.info("Skipping tier application for account {} due to manual override", request.getAccountId());
            }
        }

        return ResolveAccountTierResponse.builder()
                .accountId(request.getAccountId())
                .currentTier(currentTier)
                .recommendedTier(recommendedTier)
                .tierApplied(tierApplied)
                .manualOverrideActive(manualOverride && !request.isForceRecalculation())
                .resolutionReason(calculation.reason)
                .tierScore(calculation.score)
                .build();
    }

    /**
     * Calculate the appropriate tier based on business rules.
     */
    private TierCalculation calculateTier(ResolveAccountTierRequest request) {
        int score = 0;
        StringBuilder reason = new StringBuilder();
        AccountTier tier = AccountTier.STANDARD;

        // Revenue-based calculation
        if (request.getAnnualRevenue() != null) {
            BigDecimal revenue = request.getAnnualRevenue();
            if (revenue.compareTo(ENTERPRISE_THRESHOLD) >= 0) {
                tier = AccountTier.ENTERPRISE;
                score = 600;
                reason.append("Annual revenue >= $1M. ");
            } else if (revenue.compareTo(PLATINUM_THRESHOLD) >= 0) {
                tier = AccountTier.PLATINUM;
                score = 500;
                reason.append("Annual revenue >= $500K. ");
            } else if (revenue.compareTo(GOLD_THRESHOLD) >= 0) {
                tier = AccountTier.GOLD;
                score = 400;
                reason.append("Annual revenue >= $250K. ");
            } else if (revenue.compareTo(SILVER_THRESHOLD) >= 0) {
                tier = AccountTier.SILVER;
                score = 300;
                reason.append("Annual revenue >= $100K. ");
            } else if (revenue.compareTo(BRONZE_THRESHOLD) >= 0) {
                tier = AccountTier.BRONZE;
                score = 200;
                reason.append("Annual revenue >= $50K. ");
            }
        }

        // Contract-based upgrade
        if (request.getActiveContractCount() != null) {
            int contracts = request.getActiveContractCount();
            if (contracts >= PLATINUM_CONTRACTS && tier.ordinal() < AccountTier.PLATINUM.ordinal()) {
                tier = AccountTier.PLATINUM;
                score = Math.max(score, 500);
                reason.append("5+ active contracts. ");
            } else if (contracts >= GOLD_CONTRACTS && tier.ordinal() < AccountTier.GOLD.ordinal()) {
                tier = AccountTier.GOLD;
                score = Math.max(score, 400);
                reason.append("3+ active contracts. ");
            } else if (contracts >= SILVER_CONTRACTS && tier.ordinal() < AccountTier.SILVER.ordinal()) {
                tier = AccountTier.SILVER;
                score = Math.max(score, 300);
                reason.append("2+ active contracts. ");
            }
        }

        // Age-based upgrade (minimum BRONZE for established accounts)
        if (request.getAccountAgeMonths() != null) {
            int ageMonths = request.getAccountAgeMonths();
            if (ageMonths >= BRONZE_AGE_MONTHS && tier == AccountTier.STANDARD) {
                tier = AccountTier.BRONZE;
                score = Math.max(score, 200);
                reason.append("Account age >= 3 months. ");
            }
        }

        if (reason.length() == 0) {
            reason.append("Default tier assignment. ");
        }

        return new TierCalculation(tier, score, reason.toString().trim());
    }

    /**
     * Apply a tier to a party entity.
     */
    private void applyTierToParty(AbstractParty party, AccountTier tier, String reason, boolean forceRecalc) {
        party.setTier(tier);
        party.setTierAssignedAt(Instant.now());
        party.setTierAssignedBy("SYSTEM"); // Could be enhanced to track actual user

        if (forceRecalc) {
            party.setTierManualOverride(false);
        }

        log.debug("Applied tier {} to party {}: {}", tier, party.getPartyId(), reason);
    }

    /**
     * Build a tier response from a party entity.
     */
    private GetAccountTierResponse buildTierResponse(CommercialParty party) {
        AccountTier tier = party.getTier() != null ? party.getTier() : AccountTier.STANDARD;

        return GetAccountTierResponse.builder()
                .accountId(party.getPartyId().toString())
                .tier(tier)
                .tierDisplayName(tier.getDisplayName())
                .tierAssignedAt(party.getTierAssignedAt())
                .tierAssignedBy(party.getTierAssignedBy())
                .manualOverride(party.isTierManualOverride())
                .build();
    }

    /**
     * Internal record for tier calculation results.
     */
    public record TierCalculation(AccountTier tier, int score, String reason) {
    }
}
