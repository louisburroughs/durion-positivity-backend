package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.client.CatalogProductCodeClient;
import com.positivity.inventory.internal.config.SupplierStockHintProperties;
import com.positivity.inventory.internal.entity.SupplierStockHint;
import com.positivity.inventory.internal.enums.SupplierHintResolutionStatus;
import com.positivity.inventory.internal.repository.SupplierStockHintRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ties supplier stock hints to catalog products, out of band (CAP-322, #1312).
 *
 * <p>Resolution is a sweep and not part of the consumer for one practical reason: a chunk carries
 * up to several hundred lines, pos-inventory can only resolve by asking pos-catalog, and hundreds
 * of synchronous calls inside a Kafka apply transaction would hold that transaction open across the
 * network. Hints therefore land unresolved and become resolved later, which also matches what
 * resolution actually is here — a fact about our catalog data that can change without the vendor
 * saying anything new.
 *
 * <p>Only EAN is matched. The vendor's own article code and our code as the vendor holds it are
 * deliberately left alone: they carry no per-scheme uniqueness guarantee, and the producer's
 * warning that PRICAT's matching rules may be wrong for a stock report applies squarely to them.
 * A hint with no EAN is marked {@code NOT_RESOLVABLE} rather than guessed at, and stays fully
 * visible through the code-based read path.
 *
 * <p>Failures are not terminal. A hint whose lookup could not be made stays {@code PENDING} for the
 * next pass, and a hint that matched nothing returns to {@code PENDING} the next time its vendor
 * reports it — so an article that catalog gains later resolves on the vendor's own schedule without
 * a retry timer of its own.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.inventory.supplier-hints.resolution", name = "enabled", havingValue = "true")
public class SupplierStockHintResolver {

    private static final String EAN = "EAN";
    private static final String RESOLVED_BY = "catalog:EAN";

    private final SupplierStockHintRepository hintRepository;
    private final CatalogProductCodeClient catalogProductCodeClient;
    private final SupplierStockHintProperties properties;
    private final Clock clock;

    @Scheduled(
            fixedDelayString = "${pos.inventory.supplier-hints.resolution.interval-ms:300000}",
            initialDelayString = "${pos.inventory.supplier-hints.resolution.initial-delay-ms:60000}")
    public void resolvePending() {
        try {
            ResolutionPassResult result = runResolutionPass();
            if (result.attempted() > 0) {
                log.info(
                        "Supplier stock-hint resolution pass: attempted={} resolved={} unresolved={}"
                                + " notResolvable={} deferred={}",
                        result.attempted(),
                        result.resolved(),
                        result.unresolved(),
                        result.notResolvable(),
                        result.deferred());
            }
        } catch (Exception ex) {
            // Scheduled job: one failed pass never escalates; the backlog is still there next pass.
            log.error("Supplier stock-hint resolution pass failed", ex);
        }
    }

    /**
     * Resolves one bounded slice of the pending backlog, oldest fetch first.
     *
     * <p>Each hint is saved on its own rather than the batch being saved at the end, so a pass that
     * dies halfway keeps the work it already did. No surrounding transaction is opened: this calls
     * out over the network per hint, and a transaction spanning those calls would hold a database
     * connection for the length of a remote round trip per row.
     */
    ResolutionPassResult runResolutionPass() {
        List<SupplierStockHint> pending = hintRepository.findByResolutionStatusOrderByFetchedAtAsc(
                SupplierHintResolutionStatus.PENDING,
                Limit.of(Math.max(1, properties.getResolution().getBatchSize())));
        int resolved = 0;
        int unresolved = 0;
        int notResolvable = 0;
        int deferred = 0;
        for (SupplierStockHint hint : pending) {
            String ean = hint.getArticleEan();
            if (ean == null || ean.isBlank()) {
                hint.setResolutionStatus(SupplierHintResolutionStatus.NOT_RESOLVABLE);
                clearResolution(hint);
                hintRepository.save(hint);
                notResolvable++;
                continue;
            }
            try {
                Optional<CatalogProductCodeClient.ProductCodeMatchDto> match =
                        catalogProductCodeClient.findByCode(EAN, ean.trim());
                if (match.isPresent()) {
                    hint.setResolvedProductId(match.get().productId());
                    hint.setResolvedAt(Instant.now(clock));
                    hint.setResolvedBy(RESOLVED_BY);
                    hint.setResolutionStatus(SupplierHintResolutionStatus.RESOLVED);
                    resolved++;
                } else {
                    // Catalog does not carry this article, or carries it ambiguously. Keep the
                    // hint and what the vendor said; the next snapshot re-queues the attempt.
                    hint.setResolutionStatus(SupplierHintResolutionStatus.UNRESOLVED);
                    clearResolution(hint);
                    unresolved++;
                }
                hintRepository.save(hint);
            } catch (Exception ex) {
                // Catalog unreachable or erroring: leave the hint PENDING for the next pass rather
                // than recording an "unresolved" that says something about our catalog data.
                deferred++;
                log.debug("Deferring resolution of hint {} (ean={}): {}", hint.getHintId(), ean, ex.toString());
            }
        }
        return new ResolutionPassResult(pending.size(), resolved, unresolved, notResolvable, deferred);
    }

    /**
     * Drops any resolution a row is carrying when this pass concludes it has none.
     *
     * <p>A row reaching the resolver is {@code PENDING}, which the consumer sets when a snapshot
     * changes an article's EAN. If that row had previously resolved, the product it points at
     * describes the old code — so a pass that fails to resolve the new one must clear it rather
     * than leave a product hanging off a row that is no longer resolved.
     */
    private static void clearResolution(SupplierStockHint hint) {
        hint.setResolvedProductId(null);
        hint.setResolvedAt(null);
        hint.setResolvedBy(null);
    }

    /** Outcome counts for one pass. */
    record ResolutionPassResult(int attempted, int resolved, int unresolved, int notResolvable, int deferred) {}
}
