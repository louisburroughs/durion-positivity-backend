package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The paths the loader uses to look products up must be the ones pos-catalog serves.
 *
 * <h2>What this defends</h2>
 *
 * These are direct load-balanced calls to {@code http://catalog}, so they carry no gateway prefix
 * and must match {@code ProductController}'s mapping exactly. That controller is
 * {@code @RequestMapping("/v1/products")}; the loader asked for {@code /v1/catalog/products/...},
 * which pos-catalog answers with 404 "No endpoint for the requested path". Every lookup returned
 * empty, so every row that names a product by SKU or by class failed validation with "productId is
 * required (or a sku that resolves to one)" — a message that reads like missing data rather than a
 * wrong URL, which is how it survived.
 *
 * <p>The prefix is easy to get wrong because it is correct elsewhere in this module: the catalog
 * bulk-ingest endpoints really are under {@code /v1/catalog} ({@code CatalogBulkIngestController}),
 * so {@code /v1/catalog/bulk-ingest} and its siblings in {@code BatchConfiguration} are right and
 * must stay. Only the product read paths differ.
 *
 * <p>On alpha this cost 500 of 500 base prices and 15 of 16 putaway rules while the catalog itself
 * held all 501 products.
 */
@DisplayName("catalog lookup URIs match what pos-catalog serves")
class CatalogLookupUriTest {

    /** Records the URIs asked for and answers every one with an empty body. */
    private static final class RecordingContext implements ResolutionContext {
        private final List<String> uris = new ArrayList<>();

        @Override
        public <R> Optional<R> get(@NonNull String serviceId, @NonNull String uri, @NonNull Class<R> responseType) {
            uris.add(uri);
            return Optional.empty();
        }

        @Override
        public <R> Optional<R> memoize(@NonNull String cacheKey, @NonNull Supplier<Optional<R>> loader) {
            return loader.get();
        }

        @Override
        public UUID jobLocationId() {
            return UUID.fromString("01900000-0000-7000-8000-0000000000f1");
        }
    }

    @Test
    void looksProductsUpUnderTheControllersOwnPrefix() {
        RecordingContext context = new RecordingContext();

        CatalogResolutions.productId(context, "ACDL-12589782");

        assertThat(context.uris)
                .as("ProductController is @RequestMapping(\"/v1/products\") with @GetMapping(\"/search\")")
                .singleElement()
                .asString()
                .startsWith("/v1/products/search")
                .contains("sku=ACDL-12589782")
                .doesNotContain("/v1/catalog/");
    }

    @Test
    void theSkuIsCarriedIntoTheQuery() {
        RecordingContext context = new RecordingContext();

        CatalogResolutions.productId(context, "  SKU WITH SPACE  ");

        assertThat(context.uris).singleElement().asString().contains("SKU%20WITH%20SPACE");
    }

    @Test
    void thePutawayRuleSearchUsesTheSamePrefix() {
        // Asserted on the URI the strategy actually requests, not on what a stub chooses to answer.
        // ConvertedPackStrategiesTest exercises this path through a fake catalog, and a fake can
        // always be adjusted to agree with the caller — which is how the wrong prefix survived. This
        // captures the request instead.
        RecordingContext context = new RecordingContext();
        PutawayRuleLoaderRecord rule = new PutawayRuleLoaderRecord();
        rule.setMatchType("CATEGORY");
        rule.setMatchName("Engine Parts");

        new PutawayRuleLoaderStrategy().resolve(rule, context);

        assertThat(context.uris)
                .as("the putaway class lookup reads products, which pos-catalog serves at /v1/products")
                .isNotEmpty()
                .allSatisfy(u -> assertThat(u).startsWith("/v1/products/"))
                .anyMatch(u -> u.startsWith("/v1/products/search"));
    }

    @Test
    void translatesTheFilesOwnerWordIntoTheCustomerApisPartyType() {
        RecordingContext context = new RecordingContext();

        CustomerResolutions.partyId(context, "ORGANIZATION", "LKN Propane", "Vehicle owner");
        CustomerResolutions.partyId(context, "INDIVIDUAL", "Marie Evans", "Vehicle owner");

        assertThat(context.uris)
                .as("pos-customer's PartyType is PERSON/COMMERCIAL/UNKNOWN, not the file's wording")
                .anyMatch(u -> u.contains("partyType=COMMERCIAL"))
                .anyMatch(u -> u.contains("partyType=PERSON"))
                .noneMatch(u -> u.contains("partyType=ORGANIZATION"))
                .noneMatch(u -> u.contains("partyType=INDIVIDUAL"));
    }

    @Test
    void leavesAnApiSpelledPartyTypeAlone() {
        RecordingContext context = new RecordingContext();

        CustomerResolutions.partyId(context, "COMMERCIAL", "Already Correct Co", "Vehicle owner");

        assertThat(context.uris).singleElement().asString().contains("partyType=COMMERCIAL");
    }
}
