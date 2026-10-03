package com.positivity.catalog.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.catalog.PostgresSliceTestBase;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.enums.OperationCategory;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The capability-picker list (#2246) against the real PostgreSQL baseline: which services count as
 * claimable, the fixed order, and the two optional filters.
 *
 * <p>The baseline seeds services into this tenant, so every assertion narrows by the {@code zz2246}
 * prefix the fixtures share; the seeded rows never carry it.
 */
@DisplayName("Claimable service search on PostgreSQL (#2246)")
class ClaimableServiceSearchTest extends PostgresSliceTestBase {

    private static final String PREFIX = "zz2246";

    @Autowired
    private ServiceRepository services;

    private ServiceEntity bravo;
    private ServiceEntity alphaOne;
    private ServiceEntity alphaTwo;

    private ServiceEntity save(String name, String code, OperationCategory category) {
        ServiceEntity service = new ServiceEntity();
        service.setName(name);
        service.setOperationCode(code);
        service.setOperationCategory(category);
        return services.saveAndFlush(service);
    }

    @BeforeEach
    void fixtures() {
        bravo = save(PREFIX + " Bravo", "ZZ2246-BRK", OperationCategory.REPAIR);
        alphaOne = save(PREFIX + " Alpha", "ZZ2246-OIL", OperationCategory.MAINTENANCE);
        alphaTwo = save(PREFIX + " Alpha", "ZZ2246-ROT", OperationCategory.TIRE_SERVICE);
        save(PREFIX + " Aardvark no code", null, OperationCategory.REPAIR);
    }

    private Page<ServiceEntity> find(OperationCategory category, String q, int page, int size) {
        return services.findClaimable(category, q, PageRequest.of(page, size));
    }

    @Test
    @DisplayName("a service without an operation code is never listed")
    void excludesServicesWithoutAnOperationCode() {
        assertThat(find(null, PREFIX, 0, 50).getContent())
                .extracting(ServiceEntity::getOperationCode)
                .doesNotContainNull()
                .hasSize(3);
        assertThat(find(null, null, 0, 200).getContent())
                .allSatisfy(service -> assertThat(service.getOperationCode()).isNotNull());
    }

    @Test
    @DisplayName("ordered by name, with id breaking a tie, whatever sort the caller passes")
    void ordersByNameThenId() {
        UUID firstAlpha = alphaOne.getId().compareTo(alphaTwo.getId()) < 0 ? alphaOne.getId() : alphaTwo.getId();
        UUID secondAlpha = firstAlpha.equals(alphaOne.getId()) ? alphaTwo.getId() : alphaOne.getId();

        assertThat(services.findClaimable(null, PREFIX, PageRequest.of(0, 50, Sort.by(Sort.Order.desc("name"))))
                        .getContent())
                .extracting(ServiceEntity::getId)
                .containsExactly(firstAlpha, secondAlpha, bravo.getId());
    }

    @Test
    @DisplayName("pages are positioned by page number and size, with the total counted")
    void pages() {
        Page<ServiceEntity> second = find(null, PREFIX, 1, 2);

        assertThat(second.getTotalElements()).isEqualTo(3);
        assertThat(second.getTotalPages()).isEqualTo(2);
        assertThat(second.getContent()).extracting(ServiceEntity::getId).containsExactly(bravo.getId());
    }

    @Test
    @DisplayName("the category filter narrows to one category")
    void filtersByCategory() {
        assertThat(find(OperationCategory.TIRE_SERVICE, PREFIX, 0, 50).getContent())
                .extracting(ServiceEntity::getId)
                .containsExactly(alphaTwo.getId());
    }

    @Test
    @DisplayName("q matches the name or the operation code, ignoring case")
    void matchesNameOrCodeIgnoringCase() {
        assertThat(find(null, "zz2246 BRAVO", 0, 50).getContent())
                .extracting(ServiceEntity::getId)
                .containsExactly(bravo.getId());
        assertThat(find(null, "zz2246-oil", 0, 50).getContent())
                .extracting(ServiceEntity::getId)
                .containsExactly(alphaOne.getId());
    }

    @Test
    @DisplayName("LIKE wildcards in q are matched literally")
    void wildcardsAreLiteral() {
        assertThat(find(null, PREFIX + "%", 0, 50).getContent()).isEmpty();
        assertThat(find(null, "zz2246_", 0, 50).getContent()).isEmpty();
    }
}
