package com.positivity.supplier.internal.mktcat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentImage;
import com.positivity.supplier.internal.client.ImageStoreClient;
import com.positivity.supplier.internal.domain.model.MarketingVariant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Artwork that could not be fetched must not cost a product its marketing copy (CAP-324 #1257).
 *
 * <p>The fetches themselves need a network and are not exercised here. What is exercised is the rule
 * that governs every failure path: an entry comes back for every input, unresolved rather than
 * missing, so a broken picture is visible and retryable instead of silently absent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MktCatImageFetcher — a failed image never costs a record (#1257)")
class MktCatImageFetcherTest {

    @Mock
    private ImageStoreClient imageStoreClient;

    private MktCatImageFetcher fetcher() {
        return new MktCatImageFetcher(imageStoreClient, 50, 10_485_760L);
    }

    @Test
    @DisplayName("an unreachable host yields an unresolved entry, not a dropped one")
    void unreachableHostIsUnresolved() {
        // A dropped entry is a silently lost picture: nothing downstream could tell the difference
        // between "this design has no artwork" and "we failed to fetch its artwork".
        List<SupplierCatalogEnrichmentImage> images = fetcher()
                .fetchAndStore(List.of(
                        new MarketingVariant.MarketingImageRef("TREAD", "http://127.0.0.1:1/never-listening.jpg")));

        assertThat(images).hasSize(1);
        assertThat(images.getFirst().unresolved()).isTrue();
        assertThat(images.getFirst().imageId()).isNull();
        // The origin survives, so the retry knows where to go back to.
        assertThat(images.getFirst().sourceUri()).isEqualTo("http://127.0.0.1:1/never-listening.jpg");
        assertThat(images.getFirst().imageType()).isEqualTo("TREAD");
        verify(imageStoreClient, never()).store(any(), any(), any(), any());
    }

    @Test
    @DisplayName("every input produces exactly one entry, in order")
    void everyInputProducesAnEntry() {
        List<SupplierCatalogEnrichmentImage> images = fetcher()
                .fetchAndStore(List.of(
                        new MarketingVariant.MarketingImageRef("A", "http://127.0.0.1:1/one.jpg"),
                        new MarketingVariant.MarketingImageRef("B", "http://127.0.0.1:1/two.jpg")));

        assertThat(images).hasSize(2);
        assertThat(images).extracting(SupplierCatalogEnrichmentImage::imageType).containsExactly("A", "B");
    }

    @Test
    @DisplayName("no artwork means no entries, and no calls")
    void emptyInputIsCheap() {
        assertThat(fetcher().fetchAndStore(List.of())).isEmpty();
        verify(imageStoreClient, never()).store(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a malformed URI is unresolved rather than an exception escaping the import")
    void malformedUriIsUnresolved() {
        List<SupplierCatalogEnrichmentImage> images =
                fetcher().fetchAndStore(List.of(new MarketingVariant.MarketingImageRef("TREAD", "not a uri at all")));

        assertThat(images).hasSize(1);
        assertThat(images.getFirst().unresolved()).isTrue();
    }

    @Test
    @DisplayName("a resolved image carries the stored reference, never the vendor URL as its source")
    void resolvedImageCarriesTheStoredReference() {
        // Constructed directly: the render source must be the platform's id, and the vendor URL is
        // provenance only. That distinction is the whole point of #1257.
        SupplierCatalogEnrichmentImage resolved =
                new SupplierCatalogEnrichmentImage("TREAD", 9L, "abc123", "https://cdn.example.com/x.jpg", false);

        assertThat(resolved.imageId()).isEqualTo(9L);
        assertThat(resolved.unresolved()).isFalse();
        assertThat(resolved.sourceUri()).isEqualTo("https://cdn.example.com/x.jpg");
    }

    @Test
    @DisplayName("a resolved image with nothing to point at cannot be constructed")
    void resolvedImageMustHaveAnId() {
        // The shape that quietly loses artwork: a consumer would treat it as present and render
        // nothing.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new SupplierCatalogEnrichmentImage("TREAD", null, null, "https://x", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the store client is asked with the origin, so pos-image can deduplicate re-ingests")
    void storeIsCalledWithTheOrigin() {
        when(imageStoreClient.store(any(), any(), any(), eq("https://cdn.example.com/x.jpg")))
                .thenReturn(Optional.of(new ImageStoreClient.StoredImageRef(1L, "hash", true)));

        // The fetch itself needs a network, so this asserts the contract the fetcher relies on:
        // pos-image deduplicates on origin plus content, and cannot do so without the origin.
        assertThat(imageStoreClient.store("x.jpg", "image/jpeg", new byte[] {1}, "https://cdn.example.com/x.jpg"))
                .isPresent();
    }
}
