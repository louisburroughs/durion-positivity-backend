package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.warranty.internal.dto.PolicyRequest;
import com.positivity.warranty.internal.dto.PolicyResponse;
import com.positivity.warranty.internal.entity.WarrantyPolicy;
import com.positivity.warranty.internal.enums.AppliesToType;
import com.positivity.warranty.internal.enums.CoverageType;
import com.positivity.warranty.internal.enums.ProrationMethod;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.repository.WarrantyPolicyRepository;
import com.positivity.warranty.internal.repository.WarrantyProviderRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Warranty policy CRUD (PRD §3.2): cross-field appliesTo validation the bean-validation
 * annotations cannot express, effective-date window sanity, provider existence, list filter
 * branches, and {@code findApplicable} specificity ordering (PRD §6 step 1).
 */
@ExtendWith(MockitoExtension.class)
class PolicyServiceImplTest {

    private static final UUID POLICY_ID = UUID.fromString("018f0000-0000-7000-8000-000000000201");
    private static final UUID PROVIDER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000202");
    private static final UUID PRODUCT_A = UUID.fromString("018f0000-0000-7000-8000-00000000020a");
    private static final UUID PRODUCT_B = UUID.fromString("018f0000-0000-7000-8000-00000000020b");
    private static final UUID MANUFACTURER_ID = UUID.fromString("018f0000-0000-7000-8000-00000000020c");
    private static final UUID CATEGORY_ID = UUID.fromString("018f0000-0000-7000-8000-00000000020d");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate SALE_DATE = LocalDate.of(2026, 6, 15);

    @Mock
    private WarrantyPolicyRepository policyRepository;

    @Mock
    private WarrantyProviderRepository providerRepository;

    @InjectMocks
    private PolicyServiceImpl service;

    private static PolicyRequest request(
            AppliesToType appliesToType,
            List<UUID> productIds,
            UUID categoryId,
            UUID manufacturerId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        return new PolicyRequest(
                PROVIDER_ID,
                "Road hazard 36 months",
                CoverageType.ROAD_HAZARD,
                appliesToType,
                productIds,
                categoryId,
                manufacturerId,
                effectiveFrom,
                effectiveTo,
                36,
                40_000,
                2,
                Boolean.TRUE,
                new BigDecimal("1.50"),
                new BigDecimal("120.0000"),
                ProrationMethod.TREAD_DEPTH,
                Boolean.TRUE,
                Boolean.TRUE,
                null,
                null,
                "fine print",
                List.of("https://docs.example.com/policy.pdf"));
    }

    private static PolicyRequest allScopedRequest() {
        return request(AppliesToType.ALL, null, null, null, FROM, null);
    }

    private void stubProviderExists() {
        when(providerRepository.existsById(PROVIDER_ID)).thenReturn(true);
    }

    private void stubSaveEcho() {
        when(policyRepository.save(any(WarrantyPolicy.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static WarrantyPolicy policy(AppliesToType type, String name, LocalDate effectiveFrom) {
        return WarrantyPolicy.builder()
                .id(UUID.randomUUID())
                .providerId(PROVIDER_ID)
                .name(name)
                .coverageType(CoverageType.ROAD_HAZARD)
                .appliesToType(type)
                .effectiveFrom(effectiveFrom)
                .prorationMethod(ProrationMethod.NONE)
                .build();
    }

    @Nested
    class Create {

        @Test
        void mapsFieldsAndPreservesExplicitBooleans() {
            stubProviderExists();
            stubSaveEcho();

            PolicyResponse response = service.create(request(
                    AppliesToType.PRODUCT_LIST,
                    List.of(PRODUCT_A, PRODUCT_B),
                    null,
                    null,
                    FROM,
                    LocalDate.of(2026, 12, 31)));

            assertThat(response.providerId()).isEqualTo(PROVIDER_ID);
            assertThat(response.name()).isEqualTo("Road hazard 36 months");
            assertThat(response.coverageType()).isEqualTo(CoverageType.ROAD_HAZARD);
            assertThat(response.appliesToType()).isEqualTo(AppliesToType.PRODUCT_LIST);
            assertThat(response.appliesToProductEntityIds()).containsExactly(PRODUCT_A, PRODUCT_B);
            assertThat(response.effectiveFrom()).isEqualTo(FROM);
            assertThat(response.effectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
            assertThat(response.durationMonths()).isEqualTo(36);
            assertThat(response.mileageLimit()).isEqualTo(40_000);
            assertThat(response.treadPullPointThirtySeconds()).isEqualTo(2);
            assertThat(response.laborCovered()).isTrue();
            assertThat(response.laborHoursCap()).isEqualByComparingTo("1.50");
            assertThat(response.laborRateCap()).isEqualByComparingTo("120");
            assertThat(response.prorationMethod()).isEqualTo(ProrationMethod.TREAD_DEPTH);
            assertThat(response.requiresPartReturn()).isTrue();
            assertThat(response.requiresPhotoEvidence()).isTrue();
            assertThat(response.transferable()).isFalse();
            assertThat(response.termsText()).isEqualTo("fine print");
            assertThat(response.documentUrls()).containsExactly("https://docs.example.com/policy.pdf");
        }

        @Test
        void defaultsNullBooleansToFalse() {
            stubProviderExists();
            stubSaveEcho();

            PolicyRequest request = new PolicyRequest(
                    PROVIDER_ID,
                    "Bare minimum",
                    CoverageType.DEALER_WORKMANSHIP,
                    AppliesToType.ALL,
                    null,
                    null,
                    null,
                    FROM,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    ProrationMethod.NONE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            PolicyResponse response = service.create(request);

            assertThat(response.laborCovered()).isFalse();
            assertThat(response.requiresPartReturn()).isFalse();
            assertThat(response.requiresPhotoEvidence()).isFalse();
            assertThat(response.transferable()).isFalse();
            assertThat(response.autoRegister()).isFalse();
        }

        @Test
        void unknownProviderIs404AndNothingSaved() {
            when(providerRepository.existsById(PROVIDER_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.create(allScopedRequest()))
                    .isInstanceOf(WarrantyNotFoundException.class)
                    .satisfies(ex -> assertThat(((WarrantyNotFoundException) ex).getCode())
                            .isEqualTo(ProviderServiceImpl.NOT_FOUND_CODE));
            verify(policyRepository, never()).save(any());
        }
    }

    @Nested
    class AppliesToValidation {

        @Test
        void productListWithNullIdsRejected() {
            stubProviderExists();

            assertThatThrownBy(() -> service.create(request(AppliesToType.PRODUCT_LIST, null, null, null, FROM, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("appliesToProductEntityIds");
            verify(policyRepository, never()).save(any());
        }

        @Test
        void productListWithEmptyIdsRejected() {
            stubProviderExists();

            assertThatThrownBy(() ->
                            service.create(request(AppliesToType.PRODUCT_LIST, List.of(), null, null, FROM, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("appliesToProductEntityIds");
        }

        @Test
        void categoryWithoutCategoryIdRejected() {
            stubProviderExists();

            assertThatThrownBy(() -> service.create(request(AppliesToType.CATEGORY, null, null, null, FROM, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("appliesToCategoryId");
        }

        @Test
        void manufacturerWithoutManufacturerIdRejected() {
            stubProviderExists();

            assertThatThrownBy(() -> service.create(request(AppliesToType.MANUFACTURER, null, null, null, FROM, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("appliesToManufacturerId");
        }

        @Test
        void allScopeNeedsNoScopeKey() {
            stubProviderExists();
            stubSaveEcho();

            assertThatCode(() -> service.create(allScopedRequest())).doesNotThrowAnyException();
        }

        @Test
        void effectiveToBeforeEffectiveFromRejected() {
            stubProviderExists();

            assertThatThrownBy(
                            () -> service.create(request(AppliesToType.ALL, null, null, null, FROM, FROM.minusDays(1))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("effectiveTo");
        }

        @Test
        void effectiveToEqualToEffectiveFromAllowed() {
            stubProviderExists();
            stubSaveEcho();

            assertThatCode(() -> service.create(request(AppliesToType.ALL, null, null, null, FROM, FROM)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class Update {

        @Test
        void overwritesExistingPolicy() {
            WarrantyPolicy existing = policy(AppliesToType.ALL, "old name", FROM.minusYears(1));
            existing.setId(POLICY_ID);
            when(policyRepository.findById(POLICY_ID)).thenReturn(Optional.of(existing));
            stubProviderExists();
            stubSaveEcho();

            PolicyResponse response = service.update(
                    POLICY_ID, request(AppliesToType.MANUFACTURER, null, null, MANUFACTURER_ID, FROM, null));

            assertThat(response.id()).isEqualTo(POLICY_ID);
            assertThat(response.name()).isEqualTo("Road hazard 36 months");
            assertThat(response.appliesToType()).isEqualTo(AppliesToType.MANUFACTURER);
            assertThat(response.appliesToManufacturerId()).isEqualTo(MANUFACTURER_ID);
            assertThat(response.effectiveFrom()).isEqualTo(FROM);
        }

        @Test
        void missingPolicyIs404WithCode() {
            when(policyRepository.findById(POLICY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(POLICY_ID, allScopedRequest()))
                    .isInstanceOf(WarrantyNotFoundException.class)
                    .satisfies(ex -> assertThat(((WarrantyNotFoundException) ex).getCode())
                            .isEqualTo(PolicyServiceImpl.NOT_FOUND_CODE));
        }

        @Test
        void unknownProviderOnUpdateIs404() {
            when(policyRepository.findById(POLICY_ID)).thenReturn(Optional.of(policy(AppliesToType.ALL, "old", FROM)));
            when(providerRepository.existsById(PROVIDER_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.update(POLICY_ID, allScopedRequest()))
                    .isInstanceOf(WarrantyNotFoundException.class);
            verify(policyRepository, never()).save(any());
        }

        @Test
        void crossFieldValidationAppliesOnUpdateToo() {
            when(policyRepository.findById(POLICY_ID)).thenReturn(Optional.of(policy(AppliesToType.ALL, "old", FROM)));
            stubProviderExists();

            assertThatThrownBy(() -> service.update(
                            POLICY_ID, request(AppliesToType.PRODUCT_LIST, List.of(), null, null, FROM, null)))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(policyRepository, never()).save(any());
        }
    }

    @Nested
    class GetByIdAndList {

        @Test
        void getByIdMapsEntity() {
            WarrantyPolicy existing = policy(AppliesToType.ALL, "the policy", FROM);
            existing.setId(POLICY_ID);
            when(policyRepository.findById(POLICY_ID)).thenReturn(Optional.of(existing));

            PolicyResponse response = service.getById(POLICY_ID);

            assertThat(response.id()).isEqualTo(POLICY_ID);
            assertThat(response.name()).isEqualTo("the policy");
        }

        @Test
        void getByIdMissingIs404() {
            when(policyRepository.findById(POLICY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(POLICY_ID))
                    .isInstanceOf(WarrantyNotFoundException.class)
                    .satisfies(ex -> assertThat(((WarrantyNotFoundException) ex).getCode())
                            .isEqualTo(PolicyServiceImpl.NOT_FOUND_CODE));
        }

        @Test
        void providerFilterUsesProviderFinder() {
            when(policyRepository.findByProviderId(PROVIDER_ID))
                    .thenReturn(List.of(policy(AppliesToType.ALL, "a", FROM)));

            assertThat(service.list(PROVIDER_ID, null)).hasSize(1);
            verify(policyRepository, never()).findAll();
        }

        @Test
        void providerPlusCoverageNarrowsInMemory() {
            WarrantyPolicy roadHazard = policy(AppliesToType.ALL, "rh", FROM);
            WarrantyPolicy defect = policy(AppliesToType.ALL, "md", FROM);
            defect.setCoverageType(CoverageType.MANUFACTURER_DEFECT);
            when(policyRepository.findByProviderId(PROVIDER_ID)).thenReturn(List.of(roadHazard, defect));

            List<PolicyResponse> result = service.list(PROVIDER_ID, CoverageType.MANUFACTURER_DEFECT);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().coverageType()).isEqualTo(CoverageType.MANUFACTURER_DEFECT);
        }

        @Test
        void coverageOnlyUsesCoverageFinder() {
            WarrantyPolicy extendedPlan = policy(AppliesToType.ALL, "ep", FROM);
            extendedPlan.setCoverageType(CoverageType.EXTENDED_PLAN);
            when(policyRepository.findByCoverageType(CoverageType.EXTENDED_PLAN))
                    .thenReturn(List.of(extendedPlan));

            assertThat(service.list(null, CoverageType.EXTENDED_PLAN)).hasSize(1);
            verify(policyRepository, never()).findAll();
        }

        @Test
        void unfilteredReturnsAll() {
            when(policyRepository.findAll())
                    .thenReturn(List.of(policy(AppliesToType.ALL, "a", FROM), policy(AppliesToType.ALL, "b", FROM)));

            assertThat(service.list(null, null)).hasSize(2);
        }
    }

    @Nested
    class FindApplicable {

        private WarrantyPolicy productListPolicy;
        private WarrantyPolicy manufacturerPolicy;
        private WarrantyPolicy categoryPolicy;
        private WarrantyPolicy allPolicy;

        private void stubAllFourMatching() {
            productListPolicy = policy(AppliesToType.PRODUCT_LIST, "product list", FROM);
            productListPolicy.setAppliesToProductEntityIds(List.of(PRODUCT_A, PRODUCT_B));
            manufacturerPolicy = policy(AppliesToType.MANUFACTURER, "manufacturer", FROM);
            manufacturerPolicy.setAppliesToManufacturerId(MANUFACTURER_ID);
            categoryPolicy = policy(AppliesToType.CATEGORY, "category", FROM);
            categoryPolicy.setAppliesToCategoryId(CATEGORY_ID);
            allPolicy = policy(AppliesToType.ALL, "all", FROM);
            // Deliberately least-specific-first so the assertion proves the service reorders.
            when(policyRepository.findEffectiveOn(SALE_DATE))
                    .thenReturn(List.of(allPolicy, categoryPolicy, manufacturerPolicy, productListPolicy));
        }

        @Test
        void ordersProductListBeforeManufacturerBeforeCategoryBeforeAll() {
            stubAllFourMatching();

            List<PolicyResponse> result =
                    service.findApplicable(PRODUCT_A, MANUFACTURER_ID, CATEGORY_ID, SALE_DATE, null);

            assertThat(result)
                    .extracting(PolicyResponse::appliesToType)
                    .containsExactly(
                            AppliesToType.PRODUCT_LIST,
                            AppliesToType.MANUFACTURER,
                            AppliesToType.CATEGORY,
                            AppliesToType.ALL);
        }

        @Test
        void delegatesSaleDateWindowFilteringToRepository() {
            when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of());

            assertThat(service.findApplicable(PRODUCT_A, null, null, SALE_DATE, null))
                    .isEmpty();
            verify(policyRepository).findEffectiveOn(SALE_DATE);
        }

        @Test
        void coverageTypeFilterDropsOtherCoverage() {
            stubAllFourMatching();
            productListPolicy.setCoverageType(CoverageType.MANUFACTURER_DEFECT);

            List<PolicyResponse> result = service.findApplicable(
                    PRODUCT_A, MANUFACTURER_ID, CATEGORY_ID, SALE_DATE, CoverageType.ROAD_HAZARD);

            assertThat(result)
                    .extracting(PolicyResponse::appliesToType)
                    .containsExactly(AppliesToType.MANUFACTURER, AppliesToType.CATEGORY, AppliesToType.ALL);
        }

        @Test
        void productListMatchesOnlyWhenProductIdInList() {
            stubAllFourMatching();
            UUID otherProduct = UUID.fromString("018f0000-0000-7000-8000-0000000002ff");

            List<PolicyResponse> result =
                    service.findApplicable(otherProduct, MANUFACTURER_ID, CATEGORY_ID, SALE_DATE, null);

            assertThat(result)
                    .extracting(PolicyResponse::appliesToType)
                    .containsExactly(AppliesToType.MANUFACTURER, AppliesToType.CATEGORY, AppliesToType.ALL);
        }

        @Test
        void productListSkippedWhenNoProductKeyProvided() {
            stubAllFourMatching();

            List<PolicyResponse> result = service.findApplicable(null, MANUFACTURER_ID, CATEGORY_ID, SALE_DATE, null);

            assertThat(result).extracting(PolicyResponse::appliesToType).doesNotContain(AppliesToType.PRODUCT_LIST);
        }

        @Test
        void productListWithNullStoredListNeverMatches() {
            WarrantyPolicy broken = policy(AppliesToType.PRODUCT_LIST, "no list", FROM);
            when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(broken));

            assertThat(service.findApplicable(PRODUCT_A, null, null, SALE_DATE, null))
                    .isEmpty();
        }

        @Test
        void manufacturerScopeRequiresEqualKey() {
            stubAllFourMatching();
            UUID otherManufacturer = UUID.fromString("018f0000-0000-7000-8000-0000000002fe");

            List<PolicyResponse> result =
                    service.findApplicable(PRODUCT_A, otherManufacturer, CATEGORY_ID, SALE_DATE, null);

            assertThat(result).extracting(PolicyResponse::appliesToType).doesNotContain(AppliesToType.MANUFACTURER);
        }

        @Test
        void categoryScopeSkippedWhenNoCategoryKeyProvided() {
            stubAllFourMatching();

            List<PolicyResponse> result = service.findApplicable(PRODUCT_A, MANUFACTURER_ID, null, SALE_DATE, null);

            assertThat(result).extracting(PolicyResponse::appliesToType).doesNotContain(AppliesToType.CATEGORY);
        }

        @Test
        void allScopeMatchesEvenWithNoKeysProvided() {
            stubAllFourMatching();

            List<PolicyResponse> result = service.findApplicable(null, null, null, SALE_DATE, null);

            assertThat(result).extracting(PolicyResponse::appliesToType).containsExactly(AppliesToType.ALL);
        }

        @Test
        void sameSpecificityBreaksTiesByNewerEffectiveFromThenName() {
            WarrantyPolicy older = policy(AppliesToType.ALL, "b-older", FROM.minusYears(1));
            WarrantyPolicy newer = policy(AppliesToType.ALL, "c-newer", FROM);
            WarrantyPolicy newerSameDate = policy(AppliesToType.ALL, "a-newer", FROM);
            when(policyRepository.findEffectiveOn(SALE_DATE)).thenReturn(List.of(older, newer, newerSameDate));

            List<PolicyResponse> result = service.findApplicable(null, null, null, SALE_DATE, null);

            assertThat(result).extracting(PolicyResponse::name).containsExactly("a-newer", "c-newer", "b-older");
        }
    }
}
