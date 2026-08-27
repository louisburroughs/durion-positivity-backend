package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * The {@code ext_product}-backed classification lookup the putaway rule matcher reads (#1514).
 *
 * <p>The contract that matters to a matcher is what "unresolvable" means: an unknown SKU, a stock
 * item id that is not a product id, and a replicated product carrying no classification are all
 * empty rather than a partly-populated ref, so a matcher never matches on half an answer.
 */
class ReplicaSkuCategoryLookupTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000e01");
    private static final UUID OTHER_PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000e02");
    private static final UUID CATEGORY_ID = UUID.fromString("018f0000-0000-7000-8000-000000000e03");
    private static final UUID SUBCATEGORY_ID = UUID.fromString("018f0000-0000-7000-8000-000000000e04");

    private final ExtProductReplicaRepository repository = mock(ExtProductReplicaRepository.class);
    private final ReplicaSkuCategoryLookup lookup = new ReplicaSkuCategoryLookup(repository);

    private static ExtProductReplica replica(UUID productId, String categoryName, String subcategoryName) {
        return ExtProductReplica.builder()
                .productId(productId)
                .trackingLevel("NONE")
                .categoryId(categoryName == null ? null : CATEGORY_ID)
                .categoryName(categoryName)
                .subcategoryId(subcategoryName == null ? null : SUBCATEGORY_ID)
                .subcategoryName(subcategoryName)
                .aggregateVersion(1L)
                .build();
    }

    @Test
    @DisplayName("Carries the ids and the names the matcher matches on")
    void carriesIdsAndNames() {
        when(repository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(replica(PRODUCT_ID, "Electrical System", "Batteries")));

        SkuCategoryLookup.SkuCategoryRef ref =
                lookup.categoryRefOf(PRODUCT_ID.toString()).orElseThrow();

        assertThat(ref.categoryId()).isEqualTo(CATEGORY_ID);
        assertThat(ref.categoryName()).isEqualTo("Electrical System");
        assertThat(ref.subcategoryId()).isEqualTo(SUBCATEGORY_ID);
        assertThat(ref.subcategoryName()).isEqualTo("Batteries");
    }

    @Test
    @DisplayName("An unknown SKU resolves to empty, not to a fabricated classification")
    void unknownSkuResolvesToEmpty() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThat(lookup.categoryRefOf(PRODUCT_ID.toString())).isEmpty();
    }

    @Test
    @DisplayName("A category with no subcategory still yields a ref, with nulls below it")
    void categoryWithoutSubcategory() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(replica(PRODUCT_ID, "Exhaust System", null)));

        SkuCategoryLookup.SkuCategoryRef ref =
                lookup.categoryRefOf(PRODUCT_ID.toString()).orElseThrow();

        assertThat(ref.categoryName()).isEqualTo("Exhaust System");
        assertThat(ref.subcategoryId()).isNull();
        assertThat(ref.subcategoryName()).isNull();
    }

    @Test
    @DisplayName("A replicated product carrying no classification at all yields no ref")
    void unclassifiedProductYieldsNoRef() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(replica(PRODUCT_ID, null, null)));

        assertThat(lookup.categoryRefOf(PRODUCT_ID.toString())).isEmpty();
    }

    @Test
    @DisplayName("A blank replicated name nulls the name but keeps the id — the product is still classified")
    void blankNameKeepsTheId() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(replica(PRODUCT_ID, "   ", null)));

        SkuCategoryLookup.SkuCategoryRef ref =
                lookup.categoryRefOf(PRODUCT_ID.toString()).orElseThrow();

        // A matcher matches on the id, so a blank name snapshot must not erase the classification.
        assertThat(ref.categoryId()).isEqualTo(CATEGORY_ID);
        assertThat(ref.categoryName()).isNull();
    }

    @Test
    @DisplayName("A stock item id that is not a product UUID resolves to empty without touching the DB")
    void nonUuidStockItemIdResolvesToEmpty() {
        assertThat(lookup.categoryRefOf("SKU-NOT-A-UUID")).isEmpty();

        verify(repository, never()).findById(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("The batch variant resolves many SKUs in one round trip and omits the unresolvable")
    void batchResolvesInOneRoundTrip() {
        when(repository.findAllById(ArgumentMatchers.anyIterable()))
                .thenReturn(List.of(
                        replica(PRODUCT_ID, "Electrical System", "Batteries"), replica(OTHER_PRODUCT_ID, null, null)));

        var refs =
                lookup.categoryRefOfAll(Set.of(PRODUCT_ID.toString(), OTHER_PRODUCT_ID.toString(), "SKU-NOT-A-UUID"));

        assertThat(refs).containsOnlyKeys(PRODUCT_ID.toString());
        assertThat(refs.get(PRODUCT_ID.toString()).categoryName()).isEqualTo("Electrical System");
        verify(repository, never()).findById(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("An empty batch short-circuits without a query")
    void emptyBatchShortCircuits() {
        assertThat(lookup.categoryRefOfAll(Set.of())).isEmpty();

        verify(repository, never()).findAllById(ArgumentMatchers.anyIterable());
    }

    @Test
    @DisplayName("A batch of only unparseable ids short-circuits without a query")
    void batchOfOnlyUnparseableIdsShortCircuits() {
        assertThat(lookup.categoryRefOfAll(Set.of("SKU-A", "SKU-B"))).isEmpty();

        verify(repository, never()).findAllById(ArgumentMatchers.anyIterable());
    }
}
