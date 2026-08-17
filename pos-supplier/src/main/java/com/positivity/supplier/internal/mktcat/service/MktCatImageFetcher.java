package com.positivity.supplier.internal.mktcat.service;

import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentImage;
import com.positivity.supplier.internal.client.ImageStoreClient;
import com.positivity.supplier.internal.domain.model.MarketingVariant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fetches manufacturer artwork and hands it to pos-image (CAP-324 #1257).
 *
 * <h2>Why the bytes are copied at all</h2>
 *
 * Publishing the catalogue's own image URL as the render source would put a third party in front of
 * every product page view — its uptime, its rate limits, its decision to move a file — and leave the
 * platform nothing to cache. So the bytes are fetched once at ingestion and stored.
 *
 * <h2>Every failure is an unresolved image, never a lost record</h2>
 *
 * A refused connection, a 404, a body that is empty, an image larger than the cap: all of them
 * produce an unresolved entry rather than an exception. The enrichment publishes with its text and
 * its other pictures intact, and the missing one is retried. The alternative — failing the variant —
 * would discard the marketing copy that did arrive because a picture did not.
 *
 * <h2>Fetched directly, not through the supplier base client</h2>
 *
 * The base client speaks to a configured vendor endpoint with vendor credentials and records an
 * exchange audit row per call. An image URI is an arbitrary asset URL published inside a document;
 * sending vendor credentials to it would leak them to whatever host the catalogue happens to name.
 */
@Slf4j
@Component
public class MktCatImageFetcher {

    private final ImageStoreClient imageStoreClient;
    private final HttpClient httpClient;

    /**
     * Largest artwork this will accept.
     *
     * <p>Bounded because the URI comes from a document rather than from configuration: without a cap
     * a mistyped link to a very large file would be pulled into memory and stored.
     */
    private final long maxBytes;

    public MktCatImageFetcher(
            ImageStoreClient imageStoreClient,
            @Value("${pos.supplier.mktcat.image-fetch-timeout-ms:15000}") long fetchTimeoutMs,
            @Value("${pos.supplier.mktcat.image-max-bytes:10485760}") long maxBytes) {
        this.imageStoreClient = imageStoreClient;
        this.maxBytes = maxBytes;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(fetchTimeoutMs))
                // Not followed: a redirect from a catalogue asset URL can move the fetch to a host
                // nobody configured, and this request is made without vendor credentials precisely
                // so that nothing sensitive travels. Following would undermine both.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Fetches and stores each image, in order.
     *
     * @return one entry per input, resolved where the bytes were obtained and unresolved where they
     *     were not. Never shorter than the input: a dropped entry is a silently lost picture
     */
    @NonNull
    public List<SupplierCatalogEnrichmentImage> fetchAndStore(List<MarketingVariant.@NonNull MarketingImageRef> refs) {
        List<SupplierCatalogEnrichmentImage> results = new ArrayList<>(refs.size());
        for (MarketingVariant.MarketingImageRef ref : refs) {
            results.add(fetchOne(ref));
        }
        return List.copyOf(results);
    }

    @NonNull
    private SupplierCatalogEnrichmentImage fetchOne(MarketingVariant.@NonNull MarketingImageRef ref) {
        byte[] content;
        String contentType;
        try {
            HttpResponse<byte[]> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(ref.uri())).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return unresolved(ref, "HTTP " + response.statusCode());
            }
            content = response.body();
            contentType = response.headers().firstValue("content-type").orElse("application/octet-stream");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unresolved(ref, "interrupted");
        } catch (Exception e) {
            return unresolved(ref, e.toString());
        }

        if (content == null || content.length == 0) {
            // An empty body is a failed download wearing a 200. Storing it would satisfy every
            // later "do we have this?" check and the artwork would never arrive.
            return unresolved(ref, "empty body");
        }
        if (content.length > maxBytes) {
            return unresolved(ref, "exceeds " + maxBytes + " bytes");
        }

        Optional<ImageStoreClient.StoredImageRef> stored =
                imageStoreClient.store(filenameFor(ref.uri()), contentType, content, ref.uri());
        if (stored.isEmpty()) {
            return unresolved(ref, "image service did not store it");
        }
        return new SupplierCatalogEnrichmentImage(
                ref.imageType(), stored.get().imageId(), stored.get().contentHash(), ref.uri(), false);
    }

    @NonNull
    private SupplierCatalogEnrichmentImage unresolved(
            MarketingVariant.@NonNull MarketingImageRef ref, @NonNull String why) {
        log.debug("Artwork at {} is unresolved and will be retried: {}", ref.uri(), why);
        return SupplierCatalogEnrichmentImage.unresolved(ref.imageType(), ref.uri());
    }

    /**
     * A filename for the stored image, taken from the URI's last path segment.
     *
     * <p>Falls back to the whole URI's hash-free tail rather than inventing a name, so an operator
     * looking at stored artwork can still recognise where it came from.
     */
    @NonNull
    private static String filenameFor(@NonNull String uri) {
        String path = uri;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        String candidate = slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
        return candidate.isBlank() ? "mktcat-image" : candidate;
    }
}
