package com.positivity.supplier.internal.client;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Puts fetched artwork into pos-image (CAP-324 #1257).
 *
 * <h2>Why the bytes are copied rather than linked</h2>
 *
 * Publishing a manufacturer's own URL as the render source would put a third party's uptime and rate
 * limits in front of every product page view, and leave the platform nothing to cache or optimise.
 * So MKCAT ingestion fetches the bytes and hands them here; the vendor URL travels on as provenance
 * only.
 *
 * <h2>Failure returns empty rather than throwing</h2>
 *
 * A missing picture must not cost a product its marketing copy. The caller records the image as
 * unresolved, publishes everything else, and retries the image on its own — which is the behaviour
 * #1257 asks for, and it only works if a failure here is an answer rather than an exception.
 *
 * <p>pos-image is a Utility module under ADR-0044 and may be called directly.
 */
@Slf4j
@Component
public class ImageStoreClient {

    /** The authority pos-image requires of a store. */
    private static final String IMAGE_STORE_AUTHORITY = "image:image:store";

    private static final String SERVICE_USER = "pos-supplier-service";

    private final RestClient restClient;
    private final String basePath;

    public ImageStoreClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.image.service-id:image}") String serviceId,
            @Value("${pos.image.base-path:/v1/images}") String basePath) {
        this.basePath = basePath;
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    /**
     * Stores image bytes and returns the reference, or empty when it could not be done.
     *
     * @param filename    name to store it under
     * @param contentType MIME type as the vendor served it
     * @param content     the fetched bytes
     * @param sourceUri   where they came from; kept as provenance by pos-image
     * @return the stored reference, or empty when pos-image refused or could not be reached
     */
    @NonNull
    public Optional<StoredImageRef> store(
            @NonNull String filename, @NonNull String contentType, byte[] content, @NonNull String sourceUri) {
        try {
            StoredImageRef stored = restClient
                    .post()
                    .uri(basePath)
                    // Authorised like any other caller. Without these the store is rejected and
                    // every image silently becomes unresolved, which looks exactly like a vendor
                    // serving broken artwork.
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", IMAGE_STORE_AUTHORITY)
                    .body(Map.of(
                            "filename",
                            filename,
                            "contentType",
                            contentType,
                            "content",
                            Base64.getEncoder().encodeToString(content),
                            "sourceUri",
                            sourceUri,
                            "context",
                            List.of("mkcat")))
                    .retrieve()
                    .body(StoredImageRef.class);
            return Optional.ofNullable(stored);
        } catch (Exception e) {
            // Deliberately broad and deliberately not rethrown: see the class note. One picture
            // must not cost a product its entire marketing record.
            log.warn("Storing image from {} failed: {}", sourceUri, e.toString());
            return Optional.empty();
        }
    }

    /**
     * What pos-image says it stored.
     *
     * @param imageId     pos-image's identifier
     * @param contentHash SHA-256 of the stored bytes
     * @param newlyStored whether that call stored them or found them already held
     */
    public record StoredImageRef(long imageId, @Nullable String contentHash, boolean newlyStored) {}
}
