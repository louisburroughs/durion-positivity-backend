package com.positivity.supplier.internal.mktcat.service;

import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.SupplierMktCatVariantEntity;
import com.positivity.supplier.internal.repository.SupplierMktCatVariantRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Re-attempts artwork that could not be fetched (CAP-324 #1257).
 *
 * <h2>Why a separate runner</h2>
 *
 * An image fails for reasons that have nothing to do with the catalogue entry it belongs to -- a
 * transient 503 from an asset host, a link that was briefly wrong. Waiting for the next full sweep
 * to retry it would tie a picture's recovery to a cadence chosen for a completely different reason,
 * and on a nightly sweep that means a missing image stays missing for a day.
 *
 * <h2>Retrying is a re-import of the variant, not a re-fetch of the image</h2>
 *
 * The variant is imported again through the ordinary path. That costs a few more calls than fetching
 * the image alone and buys the thing that matters: the retry cannot produce an enrichment that
 * differs from what a sweep would have produced. A bespoke image-only path would be a second way to
 * build the same event, and the second way is the one that drifts.
 */
@Slf4j
@Service
public class MktCatImageRetryRunner {

    private final SupplierMktCatVariantRepository variantRepository;
    private final MktCatImporter importer;
    private final int batchSize;

    public MktCatImageRetryRunner(
            SupplierMktCatVariantRepository variantRepository,
            MktCatImporter importer,
            @Value("${pos.supplier.mktcat.image-retry-batch-size:25}") int batchSize) {
        this.variantRepository = variantRepository;
        this.importer = importer;
        this.batchSize = batchSize;
    }

    /** Re-imports variants whose artwork is still missing, least recently touched first. */
    @Scheduled(fixedDelayString = "${pos.supplier.mktcat.image-retry-interval-ms:1800000}")
    public void retryUnresolvedImages() {
        List<SupplierMktCatVariantEntity> pending =
                variantRepository.findByHasUnresolvedImagesTrueOrderByUpdatedAtAsc(Limit.of(batchSize));
        if (pending.isEmpty()) {
            return;
        }

        for (SupplierMktCatVariantEntity row : pending) {
            try {
                importer.importVariants(new SupplierRef(row.getSupplierRef()), List.of(row.getVendorVariantId()));
            } catch (Exception e) {
                // One catalogue being unreachable must not stop the others being retried. The row
                // keeps its unresolved flag, so the next run tries it again.
                log.warn(
                        "Retrying artwork for variant {} at {} failed: {}",
                        row.getVendorVariantId(),
                        row.getSupplierRef(),
                        e.toString());
            }
        }
    }

    /** How many variants one run will re-attempt. */
    int batchSize() {
        return batchSize;
    }

    @NonNull
    List<SupplierMktCatVariantEntity> pendingBatch() {
        return variantRepository.findByHasUnresolvedImagesTrueOrderByUpdatedAtAsc(Limit.of(batchSize));
    }
}
