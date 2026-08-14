package com.positivity.supplier.internal.pricecatalog.service;

import com.positivity.supplier.internal.domain.model.SupplierPriceCatalogEntry;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.PriceCatalogMatchMethod;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.repository.PriceCatalogUnmatchedLineRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Re-matches quarantined PRICAT lines against the current product-code replica and applies the ones
 * that now resolve — without asking the vendor for the document again (ADR-0053 §5).
 *
 * <h2>Why this exists</h2>
 *
 * #1224 shipped the quarantine but not this: the only way to close a quarantined line was to re-run
 * the whole import. That asks a trading partner for a document we already hold, produces a second
 * manifest for the same catalog, and is useless for lines quarantined as
 * {@link UnmatchedLineReason#CATALOG_UNAVAILABLE} — a replica gap, which has nothing to do with the
 * vendor and cannot be fixed by talking to them.
 *
 * <h2>Its own manifest, not the original's counters</h2>
 *
 * A re-application produces a new import manifest that points back at the one it healed
 * ({@code reappliedFromImportId}). The original's counters record what the vendor sent and how much
 * of it matched <em>at the time</em>; rewriting them later would destroy that record. It would also
 * contradict the chunk sequencing consumers have already applied — chunk 4 of a 3-chunk import is
 * not something a completeness check can accept, and #1308's consumer would read it as a discrepancy
 * rather than a repair.
 *
 * <h2>One manifest per origin import</h2>
 *
 * A profile's quarantine spans every import that ever left a line open, so a batch routinely mixes
 * them. Resolved lines are therefore grouped by the import they came from and committed one
 * manifest per group. Collapsing them into a single manifest would stamp every published price with
 * the first line's document identity and fetch timestamp — attributing prices to a document that
 * never contained them, and feeding the latest-selection tie-break (ADR-0053 §2) a fetch time that
 * belongs to a different fetch.
 *
 * <h2>Matching stays exactly as strict</h2>
 *
 * The same deterministic order as the original import: exact EAN, then the cross-reference code as a
 * UPC. A second chance for the catalog to have changed is not a licence for a looser match.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuarantineReapplier {

    /**
     * Reasons worth retrying. {@link UnmatchedLineReason#NO_IDENTIFIER} and
     * {@link UnmatchedLineReason#MALFORMED_LINE} are excluded on purpose: no catalog fix can rescue
     * a line that carried nothing to match on, or one whose values never decoded, so retrying them
     * would burn lookups forever and keep an operator's worklist permanently non-empty.
     */
    private static final List<UnmatchedLineReason> RETRYABLE_REASONS = List.of(
            UnmatchedLineReason.NO_CATALOG_MATCH,
            UnmatchedLineReason.AMBIGUOUS_CATALOG_MATCH,
            UnmatchedLineReason.CATALOG_UNAVAILABLE);

    private final SupplierProfileRepository profileRepository;
    private final PriceCatalogUnmatchedLineRepository unmatchedLineRepository;
    private final ProductCodeResolver productCodeResolver;
    private final QuarantineReapplicationWriter reapplicationWriter;
    private final Clock clock;

    @Value("${pos.supplier.pricat.reapply-batch-size:2000}")
    private int batchSize;

    @Value("${pos.supplier.pricat.chunk-size:500}")
    private int chunkSize;

    /**
     * Re-matches one batch of a profile's open quarantine and applies what now resolves.
     *
     * @param vendorProfileId profile whose quarantine to work
     * @return one manifest per origin import that had lines resolve; empty when nothing matched,
     *         which is the healthy steady state rather than a failure
     * @throws SupplierConfigurationException when no profile exists with that id
     */
    @NonNull
    public List<PriceCatalogImportEntity> reapply(@NonNull UUID vendorProfileId) {
        SupplierProfileEntity profile = profileRepository
                .findById(vendorProfileId)
                .orElseThrow(() -> new SupplierConfigurationException(
                        SupplierConfigurationException.UNKNOWN_SUPPLIER,
                        "No vendor profile exists with id " + vendorProfileId));

        List<PriceCatalogUnmatchedLineEntity> candidates =
                unmatchedLineRepository.findByVendorProfileIdAndResolvedAtIsNullAndReasonIn(
                        vendorProfileId, RETRYABLE_REASONS, PageRequest.of(0, Math.max(1, batchSize)));
        if (candidates.isEmpty()) {
            return List.of();
        }

        // Grouped by origin import, in encounter order, because a profile's quarantine spans every
        // import that ever left a line open and the manifest carries one document's identity.
        Map<UUID, List<ResolvedLine>> byOriginImport = new LinkedHashMap<>();
        for (PriceCatalogUnmatchedLineEntity line : candidates) {
            match(line)
                    .ifPresent(resolvedLine -> byOriginImport
                            .computeIfAbsent(line.getImportManifestId(), key -> new ArrayList<>())
                            .add(resolvedLine));
        }

        if (byOriginImport.isEmpty()) {
            log.debug(
                    "Re-application for profile {} matched none of {} candidate lines",
                    vendorProfileId,
                    candidates.size());
            return List.of();
        }

        Instant appliedAt = Instant.now(clock);
        List<PriceCatalogImportEntity> manifests = new ArrayList<>(byOriginImport.size());
        byOriginImport.forEach((originImportId, lines) -> {
            PriceCatalogImportEntity manifest = reapplicationWriter.commit(profile, lines, appliedAt, chunkSize);
            manifests.add(manifest);
            log.info(
                    "Re-applied {} quarantined lines of import {} for profile {} as import {}",
                    lines.size(),
                    originImportId,
                    vendorProfileId,
                    manifest.getImportManifestId());
        });
        return manifests;
    }

    /** Same deterministic order as the original import: exact EAN, then cross-reference as a UPC. */
    private Optional<ResolvedLine> match(PriceCatalogUnmatchedLineEntity line) {
        ResolvedLine byEan = resolve(line, line.getArticleEan(), "EAN", PriceCatalogMatchMethod.EAN);
        if (byEan != null) {
            return Optional.of(byEan);
        }
        ResolvedLine byXref = resolve(line, line.getXReferenceCode(), "UPC", PriceCatalogMatchMethod.UPC_XREFERENCE);
        return Optional.ofNullable(byXref);
    }

    @Nullable
    private ResolvedLine resolve(
            PriceCatalogUnmatchedLineEntity line,
            @Nullable String code,
            String codeType,
            PriceCatalogMatchMethod method) {
        if (code == null || code.isBlank()) {
            return null;
        }
        ProductCodeResolver.Resolution resolution = productCodeResolver.resolve(codeType, code);
        if (resolution instanceof ProductCodeResolver.Resolution.Matched matched) {
            return new ResolvedLine(line, toEntry(line), matched.productId(), method);
        }
        // Still unresolvable, still unreachable, or still ambiguous: the row stays open with its
        // original reason. Rewriting the reason on every sweep would churn the worklist without
        // telling an operator anything new.
        return null;
    }

    /** Rebuilds the canonical entry from the values the quarantine row kept (ADR-0053 §5). */
    private SupplierPriceCatalogEntry toEntry(PriceCatalogUnmatchedLineEntity line) {
        return new SupplierPriceCatalogEntry(
                line.getArticleEan(),
                line.getSupplierArticleCode(),
                line.getXReferenceCode(),
                line.getSuggestedRetailPrice(),
                line.getGrossPrice(),
                line.getNetPrice(),
                line.getTaxRate(),
                line.getRecyclingFee(),
                line.getEffectiveFrom(),
                line.getCountryCode(),
                line.getCurrency(),
                line.getSourceDocumentId(),
                line.getSourceDocumentDate(),
                line.getImportManifestId(),
                line.getPositionNumber());
    }

    /**
     * A quarantined line that now resolves, with everything the writer needs to stage and publish it.
     *
     * @param quarantineRow the row to close
     * @param entry         the canonical entry rebuilt from that row's stored values
     * @param productId     the product it now matches
     * @param method        which identifier matched
     */
    public record ResolvedLine(
            PriceCatalogUnmatchedLineEntity quarantineRow,
            SupplierPriceCatalogEntry entry,
            UUID productId,
            PriceCatalogMatchMethod method) {}
}
