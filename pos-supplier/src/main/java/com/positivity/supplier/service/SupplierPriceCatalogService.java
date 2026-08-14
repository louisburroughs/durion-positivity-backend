package com.positivity.supplier.service;

import com.positivity.supplier.service.model.PagedResponse;
import com.positivity.supplier.service.model.PriceCatalogImportSummary;
import com.positivity.supplier.service.model.UnmatchedPriceCatalogLineView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Vendor price-catalog (PRICAT) imports: on-demand triggering and import bookkeeping
 * (ADR-0053, CAP-318 #1224).
 *
 * <p>Catalog rows themselves never cross this interface. They reach consumers as
 * {@code supplier.pricecatalog.updated} events (ADR-0049 §3, ADR-0053 §7), and the absence of a
 * synchronous price read here is deliberate: it is what makes "supplier prices participate in no
 * sell-price resolution" (ADR-0053 §4) structural rather than a rule someone has to remember.
 */
public interface SupplierPriceCatalogService {

    /**
     * Returns the most recent completed price-catalog import for a vendor profile.
     *
     * @param vendorProfileId vendor profile identity (UUIDv7, ADR-0050 §1)
     * @return the latest import summary, or empty when no import ever completed
     */
    @NonNull
    Optional<PriceCatalogImportSummary> findLatestImport(@NonNull UUID vendorProfileId);

    /**
     * Lists import runs for a vendor profile, newest first, including failed and empty runs.
     *
     * <p>Failures are listed rather than hidden: "the last import failed" is the fact an operator
     * needs when prices look stale, and a listing that showed only successes would make a broken
     * feed look like a quiet one.
     *
     * @param vendorProfileId vendor profile identity
     * @param page            zero-based page index
     * @param size            page size
     * @return a page of import summaries
     */
    @NonNull
    PagedResponse<PriceCatalogImportSummary> listImports(@NonNull UUID vendorProfileId, int page, int size);

    /**
     * Lists the open unmatched-line quarantine for a vendor profile, newest first (ADR-0053 §5).
     *
     * @param vendorProfileId vendor profile identity
     * @param page            zero-based page index
     * @param size            page size
     * @return a page of quarantined lines awaiting a catalog fix
     */
    @NonNull
    PagedResponse<UnmatchedPriceCatalogLineView> listUnmatchedLines(@NonNull UUID vendorProfileId, int page, int size);

    /**
     * Runs an import on demand for one vendor profile.
     *
     * <p>The same code path as a scheduled run — identical staging, events and bookkeeping — so that
     * "reproduce it manually" remains a valid diagnostic step.
     *
     * @param vendorProfileId vendor profile to import for
     * @return the terminal summary of the run; a failed or empty fetch is a summary, not an error
     */
    @NonNull
    PriceCatalogImportSummary runImport(@NonNull UUID vendorProfileId);

    /**
     * Re-matches the profile's open quarantine against the current product-code replica and applies
     * whatever now resolves, without asking the vendor for the document again (ADR-0053 §5).
     *
     * <p>Lines that carried no identifier, or whose values never decoded, are skipped: no catalog
     * fix can rescue them, and retrying them forever would keep an operator's worklist permanently
     * non-empty.
     *
     * <p>Returns one summary per origin import that had lines resolve. A profile's quarantine spans
     * every import that ever left a line open, and each re-application manifest carries one vendor
     * document's identity — so a batch that heals lines from three imports produces three summaries,
     * not one summary attributing every price to whichever document happened to be first.
     *
     * @param vendorProfileId profile whose quarantine to work
     * @return one summary per origin import that resolved; empty when nothing matched, which is the
     *         healthy steady state rather than a failure
     */
    @NonNull
    List<PriceCatalogImportSummary> reapplyQuarantine(@NonNull UUID vendorProfileId);
}
