package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.config.SupplierStockHintProperties;
import com.positivity.inventory.internal.dto.supplierhint.SupplierStockHintView;
import com.positivity.inventory.internal.entity.SupplierStockHint;
import com.positivity.inventory.internal.entity.SupplierStockSnapshotReceipt;
import com.positivity.inventory.internal.enums.SupplierHintAvailability;
import com.positivity.inventory.internal.enums.SupplierHintIdentityKind;
import com.positivity.inventory.internal.repository.SupplierStockHintRepository;
import com.positivity.inventory.internal.repository.SupplierStockSnapshotReceiptRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read path for supplier availability hints (CAP-322, #1312).
 *
 * <p>Two things happen here that the stored row does not carry on its own.
 *
 * <p><strong>The staleness ceiling.</strong> Past its vendor's ceiling a hint's quantity is
 * suppressed and it reads as {@code STALE_UNKNOWN}. It is never rendered as zero: a figure we no
 * longer trust is an absence of knowledge, and turning it into a zero would state something the
 * vendor never said. Age is measured against the vendor's own {@code snapshotAsOf} when the vendor
 * stated one, which is why {@code asOfSource} rides along — when it says {@code FETCH}, the age is
 * our fetch clock standing in, and the caller is told so rather than shown a substitution.
 *
 * <p><strong>Snapshot completeness.</strong> Each hint names the snapshot that wrote it, and the
 * receipt for that snapshot says whether every promised chunk ever arrived. A hint from a partial
 * snapshot is still exactly what the vendor said about that article, so it is returned — flagged,
 * not withheld. What a partial snapshot costs is the guarantee that an <em>absent</em> article was
 * absent from the vendor's snapshot rather than from a chunk we lost, and that distinction is the
 * caller's to weigh.
 */
@Service
@RequiredArgsConstructor
public class SupplierStockHintServiceImpl implements SupplierStockHintService {

    private final SupplierStockHintRepository hintRepository;
    private final SupplierStockSnapshotReceiptRepository receiptRepository;
    private final SupplierStockHintProperties properties;
    private final Clock clock;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<SupplierStockHintView> findByProduct(@NonNull UUID productId) {
        return toViews(hintRepository.findByResolvedProductIdOrderByFetchedAtDesc(productId));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<SupplierStockHintView> findByCode(
            @NonNull SupplierHintIdentityKind identityKind, @NonNull String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<SupplierStockHint> hints =
                switch (identityKind) {
                    case EAN -> hintRepository.findByArticleEanOrderByFetchedAtDesc(trimmed);
                    case BUYER_ARTICLE -> hintRepository.findByBuyersArticleIdOrderByFetchedAtDesc(trimmed);
                    case SUPPLIER_ARTICLE -> hintRepository.findBySupplierArticleCodeOrderByFetchedAtDesc(trimmed);
                };
        return toViews(hints);
    }

    private List<SupplierStockHintView> toViews(List<SupplierStockHint> hints) {
        Instant now = Instant.now(clock);
        // One receipt lookup per distinct snapshot: a product's hints across vendors come from
        // different snapshots, but a vendor's hints all come from the same one.
        Map<UUID, Optional<SupplierStockSnapshotReceipt>> receipts = new HashMap<>();
        return hints.stream()
                .map(hint -> toView(
                        hint,
                        now,
                        receipts.computeIfAbsent(hint.getSourceSnapshotId(), receiptRepository::findBySnapshotId)))
                .toList();
    }

    private SupplierStockHintView toView(
            SupplierStockHint hint, Instant now, Optional<SupplierStockSnapshotReceipt> receipt) {
        boolean stale = isStale(hint, now);
        SupplierHintAvailability availability;
        Integer quantity;
        if (stale) {
            availability = SupplierHintAvailability.STALE_UNKNOWN;
            quantity = null;
        } else if (hint.getAvailableQuantity() == null) {
            availability = SupplierHintAvailability.QUANTITY_NOT_STATED;
            quantity = null;
        } else {
            availability = SupplierHintAvailability.REPORTED;
            quantity = hint.getAvailableQuantity();
        }

        return new SupplierStockHintView(
                hint.getVendorProfileId(),
                hint.getSupplierRef(),
                hint.getIdentityKind(),
                hint.getIdentityValue(),
                hint.getArticleEan(),
                hint.getSupplierArticleCode(),
                hint.getBuyersArticleId(),
                hint.getDescription(),
                hint.getResolvedProductId(),
                quantity,
                availability,
                hint.getSnapshotAsOf(),
                hint.getAsOfSource(),
                hint.getFetchedAt(),
                receipt.map(SupplierStockSnapshotReceipt::isComplete).orElse(false),
                receipt.map(SupplierStockSnapshotReceipt::getChunksApplied).orElse(0),
                receipt.map(SupplierStockSnapshotReceipt::getChunkCount).orElse(0));
    }

    private boolean isStale(SupplierStockHint hint, Instant now) {
        Duration ceiling = properties.ceilingFor(hint.getVendorProfileId());
        if (ceiling == null || ceiling.isZero() || ceiling.isNegative()) {
            // A non-positive ceiling would make every hint stale on arrival, which is not a
            // configuration anyone means; treat it as no ceiling at all.
            return false;
        }
        return Duration.between(hint.effectiveAsOf(), now).compareTo(ceiling) > 0;
    }
}
