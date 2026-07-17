package com.positivity.warranty;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.warranty.internal.config.JpaConfig;
import com.positivity.warranty.internal.dto.PolicyResponse;
import com.positivity.warranty.internal.entity.WarrantyPolicy;
import com.positivity.warranty.internal.entity.WarrantyProvider;
import com.positivity.warranty.internal.enums.AppliesToType;
import com.positivity.warranty.internal.enums.CoverageType;
import com.positivity.warranty.internal.enums.ProrationMethod;
import com.positivity.warranty.internal.enums.ProviderType;
import com.positivity.warranty.internal.repository.WarrantyPolicyRepository;
import com.positivity.warranty.internal.repository.WarrantyProviderRepository;
import com.positivity.warranty.internal.service.PolicyServiceImpl;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Runs {@code PolicyService.findApplicable} against the real Flyway schema on H2 in
 * PostgreSQL mode: proves the {@code findEffectiveOn} sale-date window (inclusive bounds,
 * open-ended {@code effective_to}) and that the {@code PRODUCT_LIST} scope round-trips the
 * jsonb product-id list and matches only ids actually in it (PRD §3.2 / §6 step 1).
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_warranty_policy_applicable;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class PolicyFindApplicableJpaTest {

    private static final UUID PRODUCT_A = UUID.fromString("018f0000-0000-7000-8000-00000000050a");
    private static final UUID PRODUCT_B = UUID.fromString("018f0000-0000-7000-8000-00000000050b");
    private static final UUID PRODUCT_OTHER = UUID.fromString("018f0000-0000-7000-8000-00000000050c");
    private static final UUID MANUFACTURER_ID = UUID.fromString("018f0000-0000-7000-8000-00000000050d");
    private static final UUID CATEGORY_ID = UUID.fromString("018f0000-0000-7000-8000-00000000050e");
    private static final LocalDate SALE_DATE = LocalDate.of(2026, 6, 15);

    @Autowired
    private WarrantyPolicyRepository policyRepository;

    @Autowired
    private WarrantyProviderRepository providerRepository;

    private PolicyServiceImpl service;
    private UUID providerId;

    @BeforeEach
    void setUp() {
        service = new PolicyServiceImpl(policyRepository, providerRepository);
        providerId = providerRepository
                .save(WarrantyProvider.builder()
                        .name("Michelin North America")
                        .providerType(ProviderType.MANUFACTURER)
                        .build())
                .getId();
    }

    private WarrantyPolicy savePolicy(
            String name, AppliesToType appliesToType, LocalDate effectiveFrom, LocalDate effectiveTo) {
        WarrantyPolicy policy = WarrantyPolicy.builder()
                .providerId(providerId)
                .name(name)
                .coverageType(CoverageType.ROAD_HAZARD)
                .appliesToType(appliesToType)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .prorationMethod(ProrationMethod.NONE)
                .build();
        return policyRepository.save(policy);
    }

    @Test
    void saleDateWindowBoundsAreInclusiveAndOpenEndedToMatches() {
        savePolicy("starts on sale date", AppliesToType.ALL, SALE_DATE, null);
        savePolicy("ends on sale date", AppliesToType.ALL, SALE_DATE.minusYears(1), SALE_DATE);
        savePolicy("open ended", AppliesToType.ALL, SALE_DATE.minusYears(1), null);
        savePolicy("expired before sale", AppliesToType.ALL, SALE_DATE.minusYears(2), SALE_DATE.minusDays(1));
        savePolicy("starts after sale", AppliesToType.ALL, SALE_DATE.plusDays(1), null);

        List<PolicyResponse> result = service.findApplicable(null, null, null, SALE_DATE, null);

        assertThat(result)
                .extracting(PolicyResponse::name)
                .containsExactlyInAnyOrder("starts on sale date", "ends on sale date", "open ended");
    }

    @Test
    void productListJsonbRoundTripsAndMatchesOnlyListedIds() {
        WarrantyPolicy listed =
                savePolicy("listed products", AppliesToType.PRODUCT_LIST, SALE_DATE.minusYears(1), null);
        listed.setAppliesToProductEntityIds(List.of(PRODUCT_A, PRODUCT_B));
        policyRepository.saveAndFlush(listed);

        PolicyResponse persisted = service.getById(listed.getId());
        assertThat(persisted.appliesToProductEntityIds()).containsExactly(PRODUCT_A, PRODUCT_B);

        assertThat(service.findApplicable(PRODUCT_B, null, null, SALE_DATE, null))
                .extracting(PolicyResponse::name)
                .containsExactly("listed products");
        assertThat(service.findApplicable(PRODUCT_OTHER, null, null, SALE_DATE, null))
                .isEmpty();
        assertThat(service.findApplicable(null, null, null, SALE_DATE, null)).isEmpty();
    }

    @Test
    void fullStackSpecificityOrderingMostSpecificFirst() {
        WarrantyPolicy listed = savePolicy("product scoped", AppliesToType.PRODUCT_LIST, SALE_DATE.minusYears(1), null);
        listed.setAppliesToProductEntityIds(List.of(PRODUCT_A));
        policyRepository.save(listed);
        WarrantyPolicy manufacturer =
                savePolicy("manufacturer scoped", AppliesToType.MANUFACTURER, SALE_DATE.minusYears(1), null);
        manufacturer.setAppliesToManufacturerId(MANUFACTURER_ID);
        policyRepository.save(manufacturer);
        WarrantyPolicy category = savePolicy("category scoped", AppliesToType.CATEGORY, SALE_DATE.minusYears(1), null);
        category.setAppliesToCategoryId(CATEGORY_ID);
        policyRepository.save(category);
        savePolicy("catch all", AppliesToType.ALL, SALE_DATE.minusYears(1), null);
        policyRepository.flush();

        List<PolicyResponse> result =
                service.findApplicable(PRODUCT_A, MANUFACTURER_ID, CATEGORY_ID, SALE_DATE, CoverageType.ROAD_HAZARD);

        assertThat(result)
                .extracting(PolicyResponse::name)
                .containsExactly("product scoped", "manufacturer scoped", "category scoped", "catch all");
    }
}
