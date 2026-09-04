package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.GetAccountTierResponse;
import com.positivity.customer.internal.dto.ResolveAccountTierRequest;
import com.positivity.customer.internal.dto.ResolveAccountTierResponse;
import com.positivity.customer.internal.entity.AbstractParty;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.enums.AccountTier;
import com.positivity.customer.internal.exception.CrmResourceNotFoundException;
import com.positivity.customer.internal.exception.CrmValidationException;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
public class AccountTierServiceImpl implements AccountTierService {
    private final Clock clock;

    private final CommercialPartyRepository commercialPartyRepository;
    private final CustomerFactPublisher customerFactPublisher;

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
     * @throws CrmResourceNotFoundException if account not found
     */
    @Override
    @Transactional(readOnly = true)
    public GetAccountTierResponse getAccountTier(@NonNull UUID accountId) {
        log.debug("Getting tier for account: {}", accountId);

        CommercialParty party = commercialPartyRepository
                .findById(accountId)
                .orElseThrow(() -> new CrmResourceNotFoundException("Account", accountId));

        return buildTierResponse(party);
    }

    /**
     * Resolve/compute the appropriate tier for an account based on business rules.
     *
     * Optionally applies the resolved tier to the account if requested.
     *
     * @param request tier resolution request with calculation criteria
     * @return resolution result with recommended tier
     * @throws CrmValidationException if the account id is not a valid UUID
     * @throws CrmResourceNotFoundException if account not found
     */
    @Override
    @Transactional
    public ResolveAccountTierResponse resolveAccountTier(@NonNull ResolveAccountTierRequest request) {
        log.info("Resolving tier for account: {} (apply={})", request.getAccountId(), request.isApplyTier());

        UUID partyId;
        try {
            partyId = UUID.fromString(request.getAccountId());
        } catch (IllegalArgumentException e) {
            // UUID.fromString: malformed accountId string, a genuine 400 (not a not-found).
            throw new CrmValidationException("Invalid account ID format: " + request.getAccountId(), e);
        }
        CommercialParty party = commercialPartyRepository
                .findById(partyId)
                .orElseThrow(() -> new CrmResourceNotFoundException("Account", request.getAccountId()));

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
                customerFactPublisher.partyChanged(party);
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
        TierCalculation calculation = new TierCalculation(AccountTier.STANDARD, 0, "");
        calculation = revenueTier(calculation, request.getAnnualRevenue());
        calculation = contractUpgrade(calculation, request.getActiveContractCount());
        calculation = ageUpgrade(calculation, request.getAccountAgeMonths());
        if (calculation.reason().isEmpty()) {
            return new TierCalculation(calculation.tier(), calculation.score(), "Default tier assignment.");
        }
        return new TierCalculation(
                calculation.tier(), calculation.score(), calculation.reason().trim());
    }

    /** The base tier, from annual revenue alone. */
    private static TierCalculation revenueTier(TierCalculation calculation, @Nullable BigDecimal revenue) {
        if (revenue == null) {
            return calculation;
        }
        if (revenue.compareTo(ENTERPRISE_THRESHOLD) >= 0) {
            return new TierCalculation(AccountTier.ENTERPRISE, 600, "Annual revenue >= $1M. ");
        }
        if (revenue.compareTo(PLATINUM_THRESHOLD) >= 0) {
            return new TierCalculation(AccountTier.PLATINUM, 500, "Annual revenue >= $500K. ");
        }
        if (revenue.compareTo(GOLD_THRESHOLD) >= 0) {
            return new TierCalculation(AccountTier.GOLD, 400, "Annual revenue >= $250K. ");
        }
        if (revenue.compareTo(SILVER_THRESHOLD) >= 0) {
            return new TierCalculation(AccountTier.SILVER, 300, "Annual revenue >= $100K. ");
        }
        if (revenue.compareTo(BRONZE_THRESHOLD) >= 0) {
            return new TierCalculation(AccountTier.BRONZE, 200, "Annual revenue >= $50K. ");
        }
        return calculation;
    }

    /**
     * Contract counts can only raise the tier. A rule whose target the account already meets is a
     * complete no-op — its reason must not be appended either, or the resolution would attribute
     * the tier to a rule that did not decide it.
     */
    private static TierCalculation contractUpgrade(TierCalculation calculation, @Nullable Integer contractCount) {
        if (contractCount == null) {
            return calculation;
        }
        if (contractCount >= PLATINUM_CONTRACTS) {
            return raiseTo(calculation, AccountTier.PLATINUM, 500, "5+ active contracts. ");
        }
        if (contractCount >= GOLD_CONTRACTS) {
            return raiseTo(calculation, AccountTier.GOLD, 400, "3+ active contracts. ");
        }
        if (contractCount >= SILVER_CONTRACTS) {
            return raiseTo(calculation, AccountTier.SILVER, 300, "2+ active contracts. ");
        }
        return calculation;
    }

    /** Age grants a minimum BRONZE to established accounts that earned nothing else. */
    private static TierCalculation ageUpgrade(TierCalculation calculation, @Nullable Integer ageMonths) {
        if (ageMonths == null || ageMonths < BRONZE_AGE_MONTHS || calculation.tier() != AccountTier.STANDARD) {
            return calculation;
        }
        return new TierCalculation(
                AccountTier.BRONZE,
                Math.max(calculation.score(), 200),
                calculation.reason() + "Account age >= 3 months. ");
    }

    private static TierCalculation raiseTo(TierCalculation calculation, AccountTier target, int score, String reason) {
        if (calculation.tier().ordinal() >= target.ordinal()) {
            return calculation;
        }
        return new TierCalculation(target, Math.max(calculation.score(), score), calculation.reason() + reason);
    }

    /**
     * Apply a tier to a party entity.
     */
    private void applyTierToParty(AbstractParty party, AccountTier tier, String reason, boolean forceRecalc) {
        party.setTier(tier);
        party.setTierAssignedAt(Instant.now(clock));
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
    public record TierCalculation(AccountTier tier, int score, String reason) {}
}
