package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.warranty.internal.client.CatalogClient;
import com.positivity.warranty.internal.dto.EligibilityEvaluation;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.entity.WarrantyClaimLine;
import com.positivity.warranty.internal.entity.WarrantyPolicy;
import com.positivity.warranty.internal.entity.WarrantyRegistration;
import com.positivity.warranty.internal.enums.AppliesToType;
import com.positivity.warranty.internal.enums.ClaimType;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Eligibility term evaluation (PRD §6) with mocked repositories/clients and the real
 * {@link ProrationService}: candidate matching (most-specific-first), per-term reason maps,
 * INDETERMINATE on missing facts, registration-bound coverage, persistence onto claim + lines,
 * and the computed {@code suggestedOutcome}.
 */
@ExtendWith(MockitoExtension.class)
class EligibilityServiceImplTest {

    private static final UUID CLAIM_ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID LINE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000002");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000003");
    private static final UUID MANUFACTURER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000004");
    private static final UUID CATEGORY_ID = UUID.fromString("018f0000-0000-7000-8000-000000000007");
    private static final UUID PROVIDER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000005");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000006");
    private static final LocalDate SALE_DATE = LocalDate.of(2026, 1, 10);
    private static final LocalDate FAILURE_DATE = LocalDate.of(2026, 6, 1);

    @Mock
    private WarrantyClaimRepository claimRepository;

    @Mock
    private WarrantyPolicyRepository policyRepository;

    @Mock
    private WarrantyRegistrationRepository registrationRepository;

    @Mock
    private CatalogClient catalogClient;

    private EligibilityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EligibilityServiceImpl(
                claimRepository, policyRepository, registrationRepository, catalogClient, new ProrationService());
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private static WarrantyClaim claim(ClaimType claimType) {
        WarrantyClaim claim = WarrantyClaim.builder()
                .id(CLAIM_ID)
                .claimCode("WC-2026-000001")
                .claimType(claimType)
                .customerId(CUSTOMER_ID)
                .originSaleDate(SALE_DATE)
                .failureDate(FAILURE_DATE)
                .originUnverified(false)
                .build();
        WarrantyClaimLine line = WarrantyClaimLine.builder()
                .id(LINE_ID)
                .productEntityId(PRODUCT_ID)
                .originalUnitPrice(new BigDecimal("100.00"))
                .quantity(BigDecimal.ONE)
                .originalTreadDepth(10)
                .measuredTreadDepth(6)
                .build();
        claim.addLine(line);
        return claim;
    }

    private static WarrantyPolicy manufacturerTreadPolicy() {
        return WarrantyPolicy.builder()
                .id(UUID.fromString("018f0000-0000-7000-8000-000000000010"))
                .providerId(PROVIDER_ID)
                .name("Michelin passenger replacement")
                .coverageType(CoverageType.MANUFACTURER_DEFECT)
                .appliesToType(AppliesToType.MANUFACTURER)
                .appliesToManufacturerId(MANUFACTURER_ID)
                .effectiveFrom(LocalDate.of(2025, 1, 1))
                .durationMonths(24)
                .treadPullPointThirtySeconds(2)
                .prorationMethod(ProrationMethod.TREAD_DEPTH)
                .build();
    }

    private static WarrantyPolicy allFreeReplacementPolicy(String name, UUID id) {
        return WarrantyPolicy.builder()
                .id(id)
                .providerId(PROVIDER_ID)
                .name(name)
                .coverageType(CoverageType.MANUFACTURER_DEFECT)
                .appliesToType(AppliesToType.ALL)
                .effectiveFrom(LocalDate.of(2025, 1, 1))
                .prorationMethod(ProrationMethod.NONE)
                .build();
    }

    private static CatalogClient.ProductInfo product() {
        return new CatalogClient.ProductInfo(
                PRODUCT_ID,
                "TIRE-1",
                "SuperTire 225",
                MANUFACTURER_ID,
                "Michelin",
                "Michelin",
                CATEGORY_ID,
                "Tires",
                null,
                null);
    }

    private void stubClaim(WarrantyClaim claim) {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(WarrantyClaim.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // -----------------------------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------------------------

    @Test
    void eligibleClaim_persistsResultProrationAndCoverageSelection() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        WarrantyPolicy policy = manufacturerTreadPolicy();
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(policy));

        EligibilityEvaluation evaluation = service.evaluate(CLAIM_ID);

        assertThat(evaluation.result()).isEqualTo(EligibilityResult.ELIGIBLE);
        // persisted onto the claim
        assertThat(claim.getEligibilityResult()).isEqualTo(EligibilityResult.ELIGIBLE);
        assertThat(claim.getEligibilityReasons()).isEqualTo(evaluation.reasons());
        assertThat(claim.getSuggestedOutcome()).isEqualTo(evaluation.suggestedOutcome());
        // single winner selected onto the claim
        assertThat(claim.getPolicyId()).isEqualTo(policy.getId());
        assertThat(claim.getProviderId()).isEqualTo(PROVIDER_ID);
        // per-line proration persisted: (6-2)/(10-2) = 0.5
        WarrantyClaimLine line = claim.getLines().get(0);
        assertThat(line.getProrationPct()).isEqualByComparingTo("0.5");
        assertThat(line.getAmountRequested()).isEqualByComparingTo("50.00");
        assertThat(line.getProrationInputs()).containsEntry("method", "TREAD_DEPTH");
        // suggested outcome
        Map<String, Object> suggested = evaluation.suggestedOutcome();
        assertThat(suggested)
                .containsEntry("eligible", true)
                .containsEntry("policyId", policy.getId().toString())
                .containsEntry("providerId", PROVIDER_ID.toString())
                .containsEntry("settlementType", "PRORATED_CREDIT");
        assertThat((BigDecimal) suggested.get("totalRequested")).isEqualByComparingTo("50.00");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> perLine = (List<Map<String, Object>>) suggested.get("perLine");
        assertThat(perLine).hasSize(1);
        assertThat(perLine.get(0)).containsEntry("claimLineId", LINE_ID.toString());
        verify(claimRepository).save(claim);
        // every reason map carries the {term, required, actual, pass} shape
        assertThat(evaluation.reasons())
                .allSatisfy(reason -> assertThat(reason).containsKeys("term", "required", "actual", "pass"));
    }

    @Test
    void treadBelowPullPoint_isIneligibleWithFailingReason() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        claim.getLines().get(0).setMeasuredTreadDepth(2); // not > pull point 2
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(manufacturerTreadPolicy()));

        EligibilityEvaluation evaluation = service.evaluate(CLAIM_ID);

        assertThat(evaluation.result()).isEqualTo(EligibilityResult.INELIGIBLE);
        assertThat(evaluation.reasons())
                .anySatisfy(reason ->
                        assertThat(reason).containsEntry("term", "treadDepth").containsEntry("pass", false));
        assertThat(evaluation.suggestedOutcome()).containsEntry("eligible", false);
    }

    @Test
    void missingTreadMeasurement_isIndeterminate() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        claim.getLines().get(0).setMeasuredTreadDepth(null);
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(manufacturerTreadPolicy()));

        EligibilityEvaluation evaluation = service.evaluate(CLAIM_ID);

        assertThat(evaluation.result()).isEqualTo(EligibilityResult.INDETERMINATE);
        assertThat(evaluation.reasons())
                .anySatisfy(reason ->
                        assertThat(reason).containsEntry("term", "treadDepth").containsEntry("indeterminate", true));
    }

    @Test
    void expiredDuration_isIneligible() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        claim.setFailureDate(SALE_DATE.plusMonths(30)); // past 24-month duration
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(manufacturerTreadPolicy()));

        EligibilityEvaluation evaluation = service.evaluate(CLAIM_ID);

        assertThat(evaluation.result()).isEqualTo(EligibilityResult.INELIGIBLE);
        assertThat(evaluation.reasons())
                .anySatisfy(reason ->
                        assertThat(reason).containsEntry("term", "duration").containsEntry("pass", false));
    }

    @Test
    void mileageLimitWithUnknownSaleOdometer_isIndeterminate() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        claim.setOdometerAtClaim(42000L);
        WarrantyPolicy policy = manufacturerTreadPolicy();
        policy.setMileageLimit(40000);
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(policy));

        EligibilityEvaluation evaluation = service.evaluate(CLAIM_ID);

        assertThat(evaluation.result()).isEqualTo(EligibilityResult.INDETERMINATE);
        assertThat(evaluation.reasons())
                .anySatisfy(reason ->
                        assertThat(reason).containsEntry("term", "mileage").containsEntry("indeterminate", true));
    }

    @Test
    void noMatchingPolicy_isIneligible() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of());

        EligibilityEvaluation evaluation = service.evaluate(CLAIM_ID);

        assertThat(evaluation.result()).isEqualTo(EligibilityResult.INELIGIBLE);
        assertThat(claim.getPolicyId()).isNull();
        assertThat(evaluation.suggestedOutcome()).containsEntry("policyId", null);
        assertThat(evaluation.suggestedOutcome()).containsEntry("settlementType", null);
    }

    @Test
    void rerunWithoutMatchingPolicy_clearsPreviouslySelectedCoverageAndProration() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE))
                .thenReturn(List.of(manufacturerTreadPolicy()))
                .thenReturn(List.of()); // second run: nothing matches (e.g. claimType corrected)

        service.evaluate(CLAIM_ID);
        assertThat(claim.getPolicyId()).isNotNull();
        assertThat(claim.getLines().get(0).getProrationPct()).isNotNull();

        EligibilityEvaluation rerun = service.evaluate(CLAIM_ID);

        assertThat(rerun.result()).isEqualTo(EligibilityResult.INELIGIBLE);
        assertThat(claim.getPolicyId()).isNull();
        assertThat(claim.getProviderId()).isNull();
        WarrantyClaimLine line = claim.getLines().get(0);
        assertThat(line.getProrationPct()).isNull();
        assertThat(line.getProrationInputs()).isNull();
        assertThat(line.getAmountRequested()).isNull();
        assertThat(rerun.suggestedOutcome())
                .containsEntry("eligible", false)
                .containsEntry("policyId", null)
                .containsEntry("providerId", null);
    }

    @Test
    void categoryPolicy_matchesByCatalogCategoryIdAndBeatsAll() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        WarrantyPolicy categoryPolicy = WarrantyPolicy.builder()
                .id(UUID.fromString("018f0000-0000-7000-8000-000000000050"))
                .providerId(PROVIDER_ID)
                .name("All tires")
                .coverageType(CoverageType.MANUFACTURER_DEFECT)
                .appliesToType(AppliesToType.CATEGORY)
                .appliesToCategoryId(CATEGORY_ID)
                .effectiveFrom(LocalDate.of(2025, 1, 1))
                .prorationMethod(ProrationMethod.NONE)
                .build();
        WarrantyPolicy broad =
                allFreeReplacementPolicy("Catch-all", UUID.fromString("018f0000-0000-7000-8000-000000000051"));
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(broad, categoryPolicy));

        service.evaluate(CLAIM_ID);

        assertThat(claim.getPolicyId()).isEqualTo(categoryPolicy.getId());
    }

    @Test
    void categoryPolicy_losesToManufacturerScopedPolicy() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        WarrantyPolicy categoryPolicy = WarrantyPolicy.builder()
                .id(UUID.fromString("018f0000-0000-7000-8000-000000000052"))
                .providerId(PROVIDER_ID)
                .name("All tires")
                .coverageType(CoverageType.MANUFACTURER_DEFECT)
                .appliesToType(AppliesToType.CATEGORY)
                .appliesToCategoryId(CATEGORY_ID)
                .effectiveFrom(LocalDate.of(2025, 1, 1))
                .prorationMethod(ProrationMethod.NONE)
                .build();
        WarrantyPolicy manufacturer = manufacturerTreadPolicy();
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(categoryPolicy, manufacturer));

        service.evaluate(CLAIM_ID);

        assertThat(claim.getPolicyId()).isEqualTo(manufacturer.getId());
    }

    @Test
    void categoryPolicy_forDifferentCategoryDoesNotMatch() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        WarrantyPolicy otherCategory = WarrantyPolicy.builder()
                .id(UUID.fromString("018f0000-0000-7000-8000-000000000053"))
                .providerId(PROVIDER_ID)
                .name("Batteries only")
                .coverageType(CoverageType.MANUFACTURER_DEFECT)
                .appliesToType(AppliesToType.CATEGORY)
                .appliesToCategoryId(UUID.fromString("018f0000-0000-7000-8000-0000000000aa"))
                .effectiveFrom(LocalDate.of(2025, 1, 1))
                .prorationMethod(ProrationMethod.NONE)
                .build();
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(otherCategory));

        EligibilityEvaluation evaluation = service.evaluate(CLAIM_ID);

        assertThat(evaluation.result()).isEqualTo(EligibilityResult.INELIGIBLE);
        assertThat(claim.getPolicyId()).isNull();
    }

    @Test
    void noMatchingPolicy_withCatalogDown_isIndeterminate() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.empty());
        // the manufacturer-scoped policy cannot match without product facts
        when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(manufacturerTreadPolicy()));

        EligibilityEvaluation evaluation = service.evaluate(CLAIM_ID);

        assertThat(evaluation.result()).isEqualTo(EligibilityResult.INDETERMINATE);
        assertThat(evaluation.reasons())
                .anySatisfy(reason ->
                        assertThat(reason).containsEntry("term", "policyMatch").containsEntry("indeterminate", true));
    }

    @Test
    void unverifiedOrigin_isIndeterminateEvenWhenTermsPass() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        claim.setOriginUnverified(true);
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE))
                .thenReturn(List.of(allFreeReplacementPolicy(
                        "Store guarantee", UUID.fromString("018f0000-0000-7000-8000-000000000011"))));

        EligibilityEvaluation evaluation = service.evaluate(CLAIM_ID);

        assertThat(evaluation.result()).isEqualTo(EligibilityResult.INDETERMINATE);
        assertThat(evaluation.reasons())
                .anySatisfy(reason -> assertThat(reason)
                        .containsEntry("term", "originVerified")
                        .containsEntry("indeterminate", true));
        // NONE proration still suggests a free replacement
        assertThat(evaluation.suggestedOutcome()).containsEntry("settlementType", "REPLACEMENT_WORKORDER");
        assertThat(claim.getLines().get(0).getProrationPct()).isEqualByComparingTo("1");
    }

    @Test
    void missingSaleDate_isIndeterminate() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        claim.setOriginSaleDate(null);
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));

        EligibilityEvaluation evaluation = service.evaluate(CLAIM_ID);

        assertThat(evaluation.result()).isEqualTo(EligibilityResult.INDETERMINATE);
        assertThat(evaluation.reasons())
                .anySatisfy(reason -> assertThat(reason)
                        .containsEntry("term", "originSaleDate")
                        .containsEntry("indeterminate", true));
        assertThat(claim.getLines().get(0).getProrationPct()).isNull();
    }

    @Test
    void registrationBoundCoverage_withoutActiveRegistration_isIneligible() {
        WarrantyClaim claim = claim(ClaimType.ROAD_HAZARD);
        WarrantyPolicy policy = manufacturerTreadPolicy();
        policy.setCoverageType(CoverageType.ROAD_HAZARD);
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(policy));
        when(registrationRepository.findByCustomerIdAndStatus(CUSTOMER_ID, RegistrationStatus.ACTIVE))
                .thenReturn(List.of());

        EligibilityEvaluation evaluation = service.evaluate(CLAIM_ID);

        assertThat(evaluation.result()).isEqualTo(EligibilityResult.INELIGIBLE);
        assertThat(evaluation.reasons())
                .anySatisfy(reason ->
                        assertThat(reason).containsEntry("term", "registration").containsEntry("pass", false));
    }

    @Test
    void registrationBoundCoverage_withActiveRegistration_selectsRegistration() {
        WarrantyClaim claim = claim(ClaimType.ROAD_HAZARD);
        WarrantyPolicy policy = manufacturerTreadPolicy();
        policy.setCoverageType(CoverageType.ROAD_HAZARD);
        WarrantyRegistration registration = WarrantyRegistration.builder()
                .id(UUID.fromString("018f0000-0000-7000-8000-000000000020"))
                .policyId(policy.getId())
                .customerId(CUSTOMER_ID)
                .purchaseDate(SALE_DATE)
                .status(RegistrationStatus.ACTIVE)
                .build();
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(policy));
        when(registrationRepository.findByCustomerIdAndStatus(CUSTOMER_ID, RegistrationStatus.ACTIVE))
                .thenReturn(List.of(registration));

        EligibilityEvaluation evaluation = service.evaluate(CLAIM_ID);

        assertThat(evaluation.result()).isEqualTo(EligibilityResult.ELIGIBLE);
        assertThat(claim.getRegistrationId()).isEqualTo(registration.getId());
    }

    @Test
    void mostSpecificPolicyWins_productListBeatsAll() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        WarrantyPolicy specific = WarrantyPolicy.builder()
                .id(UUID.fromString("018f0000-0000-7000-8000-000000000030"))
                .providerId(PROVIDER_ID)
                .name("Product-specific")
                .coverageType(CoverageType.MANUFACTURER_DEFECT)
                .appliesToType(AppliesToType.PRODUCT_LIST)
                .appliesToProductEntityIds(List.of(PRODUCT_ID))
                .effectiveFrom(LocalDate.of(2025, 1, 1))
                .prorationMethod(ProrationMethod.NONE)
                .build();
        WarrantyPolicy broad =
                allFreeReplacementPolicy("Catch-all", UUID.fromString("018f0000-0000-7000-8000-000000000031"));
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(broad, specific));

        service.evaluate(CLAIM_ID);

        assertThat(claim.getPolicyId()).isEqualTo(specific.getId());
    }

    @Test
    void multipleWinnersAtSameSpecificity_doNotSelectCoverageOntoClaim() {
        WarrantyClaim claim = claim(ClaimType.MANUFACTURER_DEFECT);
        WarrantyPolicy first =
                allFreeReplacementPolicy("Plan A", UUID.fromString("018f0000-0000-7000-8000-000000000040"));
        WarrantyPolicy second =
                allFreeReplacementPolicy("Plan B", UUID.fromString("018f0000-0000-7000-8000-000000000041"));
        stubClaim(claim);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(first, second));

        EligibilityEvaluation evaluation = service.evaluate(CLAIM_ID);

        assertThat(claim.getPolicyId()).isNull();
        assertThat(claim.getProviderId()).isNull();
        // the suggestion still names the top-sorted candidate
        assertThat(evaluation.suggestedOutcome())
                .containsEntry("policyId", first.getId().toString());
    }

    @Test
    void unknownClaim_throwsNotFound() {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluate(CLAIM_ID))
                .isInstanceOf(WarrantyNotFoundException.class)
                .hasMessageContaining(CLAIM_ID.toString());
    }
}
