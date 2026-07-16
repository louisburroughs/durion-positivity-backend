package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.client.CatalogClient;
import com.positivity.warranty.internal.dto.EligibilityEvaluation;
import com.positivity.warranty.internal.dto.ProrationOutcome;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.entity.WarrantyClaimLine;
import com.positivity.warranty.internal.entity.WarrantyPolicy;
import com.positivity.warranty.internal.entity.WarrantyRegistration;
import com.positivity.warranty.internal.enums.AppliesToType;
import com.positivity.warranty.internal.enums.CoverageType;
import com.positivity.warranty.internal.enums.EligibilityResult;
import com.positivity.warranty.internal.enums.ProrationMethod;
import com.positivity.warranty.internal.enums.RegistrationStatus;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import com.positivity.warranty.internal.repository.WarrantyPolicyRepository;
import com.positivity.warranty.internal.repository.WarrantyRegistrationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Eligibility suggestion engine (PRD §6). Candidate policies are matched most-specific-first
 * (PRODUCT_LIST &gt; MANUFACTURER &gt; CATEGORY &gt; ALL) among policies in effect on the
 * original sale date whose {@code coverageType} matches the claim type. Each structured term
 * produces a reason map {@code {term, required, actual, pass}} (pass {@code null} +
 * {@code indeterminate: true} when a fact is missing). Results are persisted onto the claim and
 * its lines; the human decision stays with staff — this only suggests.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EligibilityServiceImpl implements EligibilityService {

    static final String CLAIM_NOT_FOUND_CODE = "WARRANTY_CLAIM_NOT_FOUND";

    private final WarrantyClaimRepository claimRepository;
    private final WarrantyPolicyRepository policyRepository;
    private final WarrantyRegistrationRepository registrationRepository;
    private final CatalogClient catalogClient;
    private final ProrationService prorationService;

    @Override
    @NonNull
    @Transactional
    public EligibilityEvaluation evaluate(@NonNull UUID claimId) {
        WarrantyClaim claim = claimRepository
                .findById(claimId)
                .orElseThrow(() -> new WarrantyNotFoundException(
                        CLAIM_NOT_FOUND_CODE, "Warranty claim '" + claimId + "' was not found"));

        List<Map<String, Object>> reasons = new ArrayList<>();

        // --- Origin verification (PRD §6 step 2: "origin verified?") -----------------------
        boolean originUnverified = Boolean.TRUE.equals(claim.getOriginUnverified());
        reasons.add(reason(
                "originVerified",
                "original sale located and verified",
                !originUnverified,
                originUnverified ? null : Boolean.TRUE));

        // --- Candidate policy matching (PRD §6 step 1) --------------------------------------
        LocalDate saleDate = claim.getOriginSaleDate();
        ProductFacts facts = resolveProductFacts(claim);
        WarrantyPolicy policy = null;
        boolean singleWinner = false;
        if (saleDate == null) {
            reasons.add(reason("originSaleDate", "known original sale date", null, null));
        } else {
            List<WarrantyPolicy> candidates = findCandidatePolicies(claim, saleDate, facts);
            if (candidates.isEmpty()) {
                // Missing product facts (catalog unreachable) mean we cannot prove no policy
                // applies — indeterminate rather than a hard fail.
                reasons.add(reason(
                        "policyMatch",
                        "a policy in effect on " + saleDate + " matching the claimed products",
                        "none found",
                        facts.incomplete() ? null : Boolean.FALSE));
            } else {
                int topRank = specificityRank(candidates.get(0).getAppliesToType());
                List<WarrantyPolicy> winners = candidates.stream()
                        .filter(p -> specificityRank(p.getAppliesToType()) == topRank)
                        .toList();
                policy = winners.get(0);
                singleWinner = winners.size() == 1;
                reasons.add(reason(
                        "policyMatch",
                        "a policy in effect on " + saleDate + " matching the claimed products",
                        policy.getName() + " (" + policy.getId() + ")",
                        Boolean.TRUE));
                checkTerms(claim, policy, saleDate, reasons);
            }
        }

        // Select coverage onto the claim only when a single policy wins (PRD §6 / contract).
        if (policy != null && singleWinner) {
            claim.setPolicyId(policy.getId());
            claim.setProviderId(policy.getProviderId());
        }

        // --- Per-line proration (PRD §6 table) -----------------------------------------------
        Long monthsElapsed = saleDate != null ? ChronoUnit.MONTHS.between(saleDate, referenceDate(claim)) : null;
        // Sale-time odometer is not captured anywhere upstream today, so the delta is unknown.
        Long milesDriven = null;
        BigDecimal totalRequested = BigDecimal.ZERO.setScale(4);
        List<Map<String, Object>> perLine = new ArrayList<>();
        for (WarrantyClaimLine line : claim.getLines()) {
            BigDecimal pct = null;
            BigDecimal amount = null;
            if (policy != null) {
                ProrationOutcome outcome = prorationService.prorate(policy, line, milesDriven, monthsElapsed);
                line.setProrationPct(outcome.prorationPct());
                line.setProrationInputs(outcome.inputs());
                line.setAmountRequested(outcome.amountRequested());
                pct = outcome.prorationPct();
                amount = outcome.amountRequested();
                if (amount != null) {
                    totalRequested = totalRequested.add(amount);
                }
            }
            Map<String, Object> lineEntry = new LinkedHashMap<>();
            lineEntry.put("claimLineId", line.getId() != null ? line.getId().toString() : null);
            lineEntry.put("prorationPct", pct);
            lineEntry.put("amountRequested", amount);
            perLine.add(lineEntry);
        }

        // --- Overall result --------------------------------------------------------------------
        EligibilityResult result = overallResult(reasons);

        Map<String, Object> suggested = new LinkedHashMap<>();
        suggested.put("eligible", result == EligibilityResult.ELIGIBLE);
        suggested.put("policyId", policy != null ? policy.getId().toString() : null);
        suggested.put("providerId", policy != null ? policy.getProviderId().toString() : null);
        suggested.put("settlementType", suggestedSettlementType(policy));
        suggested.put("totalRequested", totalRequested);
        suggested.put("perLine", perLine);

        claim.setEligibilityResult(result);
        claim.setEligibilityReasons(reasons);
        claim.setSuggestedOutcome(suggested);
        claimRepository.save(claim);

        return new EligibilityEvaluation(result, reasons, suggested);
    }

    // -----------------------------------------------------------------------------------------
    // Term checks (PRD §6 step 2) — every check appends a {term, required, actual, pass} map
    // -----------------------------------------------------------------------------------------

    private void checkTerms(
            WarrantyClaim claim, WarrantyPolicy policy, LocalDate saleDate, List<Map<String, Object>> reasons) {
        LocalDate referenceDate = referenceDate(claim);

        // Duration: within durationMonths of the original sale (null = unlimited).
        Integer durationMonths = policy.getDurationMonths();
        if (durationMonths == null) {
            reasons.add(reason("duration", "unlimited", "n/a", Boolean.TRUE));
        } else {
            long monthsElapsed = ChronoUnit.MONTHS.between(saleDate, referenceDate);
            reasons.add(reason(
                    "duration",
                    "within " + durationMonths + " months of sale",
                    monthsElapsed + " months elapsed",
                    monthsElapsed <= durationMonths));
        }

        // Mileage: odometer delta since sale <= mileageLimit. The sale-time odometer is not
        // recorded upstream, so a set limit is indeterminate until that fact exists.
        Integer mileageLimit = policy.getMileageLimit();
        if (mileageLimit == null) {
            reasons.add(reason("mileage", "no mileage limit", "n/a", Boolean.TRUE));
        } else {
            reasons.add(reason(
                    "mileage",
                    "odometer delta since sale <= " + mileageLimit,
                    claim.getOdometerAtClaim() != null
                            ? "odometer at claim " + claim.getOdometerAtClaim() + ", sale odometer unknown"
                            : "odometer unknown",
                    null));
        }

        // Tread depth: measured remaining depth must exceed the pull point (tires only).
        Integer pullPoint = policy.getTreadPullPointThirtySeconds();
        if (pullPoint != null) {
            for (WarrantyClaimLine line : claim.getLines()) {
                Integer measured = line.getMeasuredTreadDepth();
                reasons.add(reasonForLine(
                        "treadDepth",
                        line,
                        "measured tread > " + pullPoint + "/32",
                        measured != null ? measured + "/32" : null,
                        measured != null ? measured > pullPoint : null));
            }
        }

        // Registration-bound coverage: ROAD_HAZARD / EXTENDED_PLAN require an active registration.
        if (policy.getCoverageType() == CoverageType.ROAD_HAZARD
                || policy.getCoverageType() == CoverageType.EXTENDED_PLAN) {
            Optional<WarrantyRegistration> registration = findActiveRegistration(claim, policy, referenceDate);
            registration.ifPresent(r -> claim.setRegistrationId(r.getId()));
            reasons.add(reason(
                    "registration",
                    "active registration for policy '" + policy.getName() + "'",
                    registration
                            .map(r -> "registration " + r.getId() + " (" + r.getStatus() + ")")
                            .orElse("none"),
                    registration.isPresent()));
        }
    }

    private Optional<WarrantyRegistration> findActiveRegistration(
            WarrantyClaim claim, WarrantyPolicy policy, LocalDate referenceDate) {
        return registrationRepository
                .findByCustomerIdAndStatus(claim.getCustomerId(), RegistrationStatus.ACTIVE)
                .stream()
                .filter(r -> policy.getId().equals(r.getPolicyId()))
                .filter(r -> r.getVehicleId() == null
                        || claim.getVehicleId() == null
                        || r.getVehicleId().equals(claim.getVehicleId()))
                .filter(r -> r.getExpiresAt() == null || !r.getExpiresAt().isBefore(referenceDate))
                .findFirst();
    }

    // -----------------------------------------------------------------------------------------
    // Candidate policy matching (PRD §6 step 1)
    // -----------------------------------------------------------------------------------------

    private List<WarrantyPolicy> findCandidatePolicies(WarrantyClaim claim, LocalDate saleDate, ProductFacts facts) {
        CoverageType coverageType = CoverageType.valueOf(claim.getClaimType().name());
        return policyRepository.findEffectiveOn(saleDate).stream()
                .filter(p -> p.getCoverageType() == coverageType)
                .filter(p -> matchesAppliesTo(p, facts))
                .sorted(Comparator.comparingInt((WarrantyPolicy p) -> specificityRank(p.getAppliesToType()))
                        .thenComparing(WarrantyPolicy::getEffectiveFrom, Comparator.reverseOrder())
                        .thenComparing(WarrantyPolicy::getName))
                .toList();
    }

    /**
     * ALL always matches; scoped policies match when any claim line resolves to the policy's
     * scope. CATEGORY policies cannot be matched today — pos-catalog exposes only a free-text
     * category name, not a category id — so they never match automatically (staff can still
     * select them by hand).
     */
    private static boolean matchesAppliesTo(WarrantyPolicy policy, ProductFacts facts) {
        return switch (policy.getAppliesToType()) {
            case PRODUCT_LIST ->
                policy.getAppliesToProductEntityIds() != null
                        && facts.productIds().stream().anyMatch(policy.getAppliesToProductEntityIds()::contains);
            case MANUFACTURER ->
                policy.getAppliesToManufacturerId() != null
                        && facts.manufacturerIds().contains(policy.getAppliesToManufacturerId());
            case CATEGORY -> false;
            case ALL -> true;
        };
    }

    /** Most-specific-first ordering: PRODUCT_LIST &gt; MANUFACTURER &gt; CATEGORY &gt; ALL. */
    private static int specificityRank(AppliesToType type) {
        return switch (type) {
            case PRODUCT_LIST -> 0;
            case MANUFACTURER -> 1;
            case CATEGORY -> 2;
            case ALL -> 3;
        };
    }

    /** Product facts (manufacturer ids) resolved from pos-catalog for lines carrying a product id. */
    private ProductFacts resolveProductFacts(WarrantyClaim claim) {
        Set<UUID> productIds = new HashSet<>();
        Set<UUID> manufacturerIds = new HashSet<>();
        boolean incomplete = false;
        Map<UUID, Optional<CatalogClient.ProductInfo>> cache = new HashMap<>();
        for (WarrantyClaimLine line : claim.getLines()) {
            UUID productEntityId = line.getProductEntityId();
            if (productEntityId == null) {
                continue;
            }
            productIds.add(productEntityId);
            Optional<CatalogClient.ProductInfo> product =
                    cache.computeIfAbsent(productEntityId, catalogClient::getProduct);
            if (product.isPresent()) {
                if (product.get().manufacturerId() != null) {
                    manufacturerIds.add(product.get().manufacturerId());
                }
            } else {
                incomplete = true;
            }
        }
        return new ProductFacts(productIds, manufacturerIds, incomplete);
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /** Failure date when captured, otherwise today — the moment eligibility is judged against. */
    private static LocalDate referenceDate(WarrantyClaim claim) {
        return claim.getFailureDate() != null ? claim.getFailureDate() : LocalDate.now();
    }

    /** INELIGIBLE on any hard fail, else INDETERMINATE on any missing fact, else ELIGIBLE. */
    private static EligibilityResult overallResult(List<Map<String, Object>> reasons) {
        boolean anyFail = reasons.stream().anyMatch(r -> Boolean.FALSE.equals(r.get("pass")));
        if (anyFail) {
            return EligibilityResult.INELIGIBLE;
        }
        boolean anyIndeterminate = reasons.stream().anyMatch(r -> r.get("pass") == null);
        return anyIndeterminate ? EligibilityResult.INDETERMINATE : EligibilityResult.ELIGIBLE;
    }

    @Nullable
    private static String suggestedSettlementType(@Nullable WarrantyPolicy policy) {
        if (policy == null) {
            return null;
        }
        return policy.getProrationMethod() == ProrationMethod.NONE ? "REPLACEMENT_WORKORDER" : "PRORATED_CREDIT";
    }

    private static Map<String, Object> reason(String term, Object required, Object actual, @Nullable Boolean pass) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("term", term);
        map.put("required", required);
        map.put("actual", actual);
        map.put("pass", pass);
        if (pass == null) {
            map.put("indeterminate", Boolean.TRUE);
        }
        return map;
    }

    private static Map<String, Object> reasonForLine(
            String term, WarrantyClaimLine line, Object required, Object actual, @Nullable Boolean pass) {
        Map<String, Object> map = reason(term, required, actual, pass);
        map.put("claimLineId", line.getId() != null ? line.getId().toString() : null);
        return map;
    }

    /** Facts resolved from claim lines; {@code incomplete} = at least one catalog lookup failed. */
    private record ProductFacts(Set<UUID> productIds, Set<UUID> manufacturerIds, boolean incomplete) {}
}
