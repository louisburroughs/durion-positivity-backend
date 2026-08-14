package com.positivity.supplier.internal.pricecatalog.service;

import com.positivity.supplier.internal.entity.ExtProductCodeReplica;
import com.positivity.supplier.internal.repository.ExtProductCodeReplicaRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a PRICAT line's identity to a catalog product against the local {@code ext_product_code}
 * replica (ADR-0053 §5, ADR-0044 R1/R3).
 *
 * <p>The replica, rather than a call to pos-catalog: ADR-0044 R1 forbids domain-to-domain
 * synchronous calls and R3 makes local replicas the sanctioned read path. The trade is staleness —
 * a product created seconds ago may not be matchable yet — and that trade is safe here because an
 * unmatched line is quarantined rather than lost, and the next import matches it.
 *
 * <p>The four outcomes are kept distinct on purpose. "No product carries this code" is catalog work;
 * "the replica is empty" means this module cannot answer the question at all and the lines are
 * recoverable; "more than one product carries it" is a replication defect that pos-catalog's
 * uniqueness constraint forbids at the source. Collapsing any of them would either strand lines a
 * retry would have matched or bury a defect as an ordinary miss.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCodeResolver {

    private final ExtProductCodeReplicaRepository replicaRepository;

    /**
     * Resolves a product by an exact code under one scheme.
     *
     * @param codeType {@code EAN} or {@code UPC}
     * @param code     exact code value; trimmed, otherwise matched verbatim
     * @return the outcome; never null
     */
    @NonNull
    @Transactional(readOnly = true)
    public Resolution resolve(@NonNull String codeType, @NonNull String code) {
        String normalized = code.trim();
        if (normalized.isEmpty()) {
            return new Resolution.NotFound();
        }
        if (replicaRepository.count() == 0) {
            // Never-seeded replica: every line would otherwise be reported as a catalog miss, which
            // would send an operator hunting for products that exist and are simply not replicated
            // here yet.
            log.warn("ext_product_code replica is empty; PRICAT lines cannot be matched yet");
            return new Resolution.Unavailable("product-code replica is empty");
        }
        List<ExtProductCodeReplica> matches = replicaRepository.findByCodeTypeAndCode(codeType, normalized);
        if (matches.isEmpty()) {
            return new Resolution.NotFound();
        }
        if (matches.size() > 1) {
            return new Resolution.Ambiguous(
                    "replica holds " + matches.size() + " products for " + codeType + " " + normalized);
        }
        return new Resolution.Matched(matches.getFirst().getProductId());
    }

    /** Outcome of one resolution. */
    public sealed interface Resolution {

        /** Exactly one product carries the code. */
        record Matched(@NonNull UUID productId) implements Resolution {}

        /** No product carries the code; a catalog fix is required before this line can apply. */
        record NotFound() implements Resolution {}

        /** More than one product carries it — matching is refused rather than guessed. */
        record Ambiguous(@Nullable String detail) implements Resolution {}

        /** The replica cannot answer; the line is re-appliable without a vendor re-fetch. */
        record Unavailable(@Nullable String detail) implements Resolution {}
    }
}
