package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.config.SupplierStockHintProperties;
import com.positivity.inventory.internal.entity.ExtProductCodeReplica;
import com.positivity.inventory.internal.entity.SupplierStockHint;
import com.positivity.inventory.internal.enums.SupplierHintResolutionStatus;
import com.positivity.inventory.internal.repository.ExtProductCodeReplicaRepository;
import com.positivity.inventory.internal.repository.SupplierStockHintRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ties supplier stock hints to catalog products, out of band (CAP-322, #1312).
 *
 * <p>Resolution reads the local {@code ext_product_code} replica, never pos-catalog directly:
 * ADR-0044 R1 forbids domain-to-domain synchronous calls and R3 makes an event-fed replica the
 * sanctioned read path. pos-supplier resolves PRICAT lines the same way against its own copy
 * (CAP-318 #1224). The trade is staleness — a product catalog created seconds ago is not matchable
 * until its fact arrives — and it is a safe trade here because an unresolved hint is retained and
 * re-queued rather than lost.
 *
 * <p>It stays a sweep rather than folding into the consumer even though the lookup is now local: a
 * chunk carries up to several hundred lines, and resolution is a fact about our catalog data that
 * changes without the vendor saying anything new, so it does not belong on the vendor's clock.
 *
 * <p>Only EAN is matched. The vendor's own article code and our code as the vendor holds it are
 * deliberately left alone: they carry no per-scheme uniqueness guarantee, and the producer's
 * warning that PRICAT's matching rules may be wrong for a stock report applies squarely to them.
 * A hint with no EAN is marked {@code NOT_RESOLVABLE} rather than guessed at, and stays fully
 * visible through the code-based read path.
 *
 * <p>Failures are not terminal. A hint the replica cannot answer for stays {@code PENDING} for the
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
    private final ExtProductCodeReplicaRepository productCodeReplicaRepository;
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
     * dies halfway keeps the work it already did.
     *
     * <p>An unseeded replica defers the hints that need it rather than marking them unresolved. "No
     * product carries this code" is a statement about our catalog; "this module has not been sent
     * the codes yet" is a statement about our replication, and reporting the second as the first
     * would send an operator hunting for products that exist and simply have not arrived here.
     * Hints carrying no EAN are settled either way, so they are not held up behind it.
     */
    ResolutionPassResult runResolutionPass() {
        List<SupplierStockHint> pending = hintRepository.findByResolutionStatusOrderByFetchedAtAsc(
                SupplierHintResolutionStatus.PENDING,
                Limit.of(Math.max(1, properties.getResolution().getBatchSize())));
        int resolved = 0;
        int unresolved = 0;
        int notResolvable = 0;
        int deferred = 0;
        // Evaluated at most once per pass, and only once some hint actually needs the replica.
        Boolean replicaSeeded = null;
        for (SupplierStockHint hint : pending) {
            String ean = hint.getArticleEan();
            if (ean == null || ean.isBlank()) {
                // Settled without consulting the replica: no EAN is no EAN whatever we hold, so
                // this verdict must not be deferred behind an unseeded replica.
                hint.setResolutionStatus(SupplierHintResolutionStatus.NOT_RESOLVABLE);
                clearResolution(hint);
                hintRepository.save(hint);
                notResolvable++;
                continue;
            }
            if (replicaSeeded == null) {
                replicaSeeded = productCodeReplicaRepository.count() > 0;
                if (!replicaSeeded) {
                    log.warn("ext_product_code replica is empty; deferring supplier stock hints that carry an EAN");
                }
            }
            if (!replicaSeeded) {
                deferred++;
                continue;
            }
            List<ExtProductCodeReplica> matches = productCodeReplicaRepository.findByCodeTypeAndCode(EAN, ean.trim());
            if (matches.size() == 1) {
                hint.setResolvedProductId(matches.getFirst().getProductId());
                hint.setResolvedAt(Instant.now(clock));
                hint.setResolvedBy(RESOLVED_BY);
                hint.setResolutionStatus(SupplierHintResolutionStatus.RESOLVED);
                resolved++;
            } else {
                if (matches.size() > 1) {
                    // pos-catalog constrains (codeType, code) at the source, so this is a
                    // replication defect rather than a catalog state. Refused rather than guessed,
                    // and logged loudly enough to be found.
                    log.warn(
                            "ext_product_code replica holds {} products for EAN {}; refusing to resolve hint {}",
                            matches.size(),
                            ean,
                            hint.getHintId());
                }
                // Keep the hint and what the vendor said; the next snapshot re-queues the attempt.
                hint.setResolutionStatus(SupplierHintResolutionStatus.UNRESOLVED);
                clearResolution(hint);
                unresolved++;
            }
            hintRepository.save(hint);
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
