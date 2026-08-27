package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The {@link SkuCategoryProvider} SPI answer (#1514) and, more importantly, which bean gives it.
 *
 * <p>The SPI's answer is the category NAME, because that is what SKU_CATEGORY-scoped sourcing and
 * costing config rows are authored against; the ids live on {@link SkuCategoryLookup}. The wiring
 * tests exist because making this step resolve is not purely additive for costing — an operator
 * needs a real lever to put the pre-#1514 fall-through back, and the {@link NoOpSkuCategoryProvider}
 * javadoc promises exactly that.
 */
class ReplicaSkuCategoryProviderTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000e01");
    private static final UUID OTHER_PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000e02");
    private static final UUID CATEGORY_ID = UUID.fromString("018f0000-0000-7000-8000-000000000e03");
    private static final UUID SUBCATEGORY_ID = UUID.fromString("018f0000-0000-7000-8000-000000000e04");

    private final SkuCategoryLookup lookup = mock(SkuCategoryLookup.class);
    private final ReplicaSkuCategoryProvider provider = new ReplicaSkuCategoryProvider(lookup);

    private static SkuCategoryLookup.SkuCategoryRef ref(String categoryName, String subcategoryName) {
        return new SkuCategoryLookup.SkuCategoryRef(
                categoryName == null ? null : CATEGORY_ID,
                categoryName,
                subcategoryName == null ? null : SUBCATEGORY_ID,
                subcategoryName);
    }

    @Test
    @DisplayName("Answers with the replicated category name")
    void answersWithTheCategoryName() {
        when(lookup.categoryRefOf(PRODUCT_ID.toString()))
                .thenReturn(Optional.of(ref("Electrical System", "Batteries")));

        assertThat(provider.categoryOf(PRODUCT_ID.toString())).contains("Electrical System");
    }

    @Test
    @DisplayName("An unresolvable SKU stays empty, exactly as the no-op answered")
    void unresolvableSkuStaysEmpty() {
        when(lookup.categoryRefOf(PRODUCT_ID.toString())).thenReturn(Optional.empty());

        assertThat(provider.categoryOf(PRODUCT_ID.toString())).isEmpty();
    }

    @Test
    @DisplayName("A product classified only by subcategory has no SKU_CATEGORY key, so it resolves to empty")
    void subcategoryOnlyProductHasNoCategoryKey() {
        when(lookup.categoryRefOf(PRODUCT_ID.toString())).thenReturn(Optional.of(ref(null, "Batteries")));

        assertThat(provider.categoryOf(PRODUCT_ID.toString())).isEmpty();
    }

    @Test
    @DisplayName("The batch variant maps names through and omits SKUs with no category name")
    void batchMapsNamesThrough() {
        when(lookup.categoryRefOfAll(Set.of(PRODUCT_ID.toString(), OTHER_PRODUCT_ID.toString())))
                .thenReturn(Map.of(
                        PRODUCT_ID.toString(), ref("Electrical System", "Batteries"),
                        OTHER_PRODUCT_ID.toString(), ref(null, "Batteries")));

        assertThat(provider.categoryOfAll(Set.of(PRODUCT_ID.toString(), OTHER_PRODUCT_ID.toString())))
                .containsExactly(Map.entry(PRODUCT_ID.toString(), "Electrical System"));
    }

    @Test
    @DisplayName("An empty batch resolves to an empty map")
    void emptyBatchResolvesToEmptyMap() {
        when(lookup.categoryRefOfAll(Set.of())).thenReturn(Map.of());

        assertThat(provider.categoryOfAll(Set.of())).isEmpty();
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withBean(ExtProductReplicaRepository.class, () -> mock(ExtProductReplicaRepository.class))
                .withUserConfiguration(ProviderBeans.class);
    }

    @Test
    @DisplayName("By default the replica provider is @Primary, so the SPI resolves the category")
    void replicaProviderIsPrimaryByDefault() {
        contextRunner().run(context -> {
            assertThat(context.getBean(SkuCategoryProvider.class)).isInstanceOf(ReplicaSkuCategoryProvider.class);
            assertThat(context.getBeansOfType(SkuCategoryProvider.class)).hasSize(2);
        });
    }

    @Test
    @DisplayName("Switching resolve-from-replica off hands the SPI back to the no-op fallback")
    void killSwitchRestoresTheNoOpFallback() {
        contextRunner()
                .withPropertyValues("pos.inventory.sku-category.resolve-from-replica=false")
                .run(context -> {
                    assertThat(context.getBean(SkuCategoryProvider.class)).isInstanceOf(NoOpSkuCategoryProvider.class);
                    assertThat(context.getBeansOfType(ReplicaSkuCategoryProvider.class))
                            .isEmpty();
                    // The lever must not take the putaway matcher's lookup down with it.
                    assertThat(context.getBean(SkuCategoryLookup.class)).isInstanceOf(ReplicaSkuCategoryLookup.class);
                });
    }

    /** The three beans that decide which implementation serves the SPI. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Import({
        ReplicaSkuCategoryLookup.class,
        ReplicaSkuCategoryProvider.class,
        NoOpSkuCategoryProvider.class
    })
    static class ProviderBeans {}
}
