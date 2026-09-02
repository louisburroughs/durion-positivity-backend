package com.positivity.supplier.internal.pricecatalog.service;

import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import com.positivity.supplier.internal.pricecatalog.service.model.PriceCatalogFreshnessView;
import com.positivity.supplier.internal.pricecatalog.service.model.PriceCatalogImportSummary;
import com.positivity.supplier.internal.pricecatalog.service.model.UnmatchedPriceCatalogLineView;
import com.positivity.supplier.internal.service.model.PagedResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
     * <p>Every filter is optional and null switches it off (#1637 decision 6). The date window
     * binds against {@code fetchedAt}, half-open ({@code fetchedFrom} inclusive,
     * {@code fetchedTo} exclusive). Runs recorded before the binding id was persisted carry none
     * and never match a {@code bindingId} filter — the documented forward-only semantics of
     * decision 4.
     *
     * @param vendorProfileId vendor profile identity
     * @param bindingId       narrow to runs fetched over one endpoint binding
     * @param status          narrow to one terminal or in-flight status
     * @param fetchedFrom     inclusive lower bound on {@code fetchedAt}
     * @param fetchedTo       exclusive upper bound on {@code fetchedAt}
     * @param page            zero-based page index
     * @param size            page size
     * @return a page of import summaries
     */
    @NonNull
    PagedResponse<PriceCatalogImportSummary> listImports(
            @NonNull UUID vendorProfileId,
            @Nullable UUID bindingId,
            @Nullable PriceCatalogImportStatus status,
            @Nullable Instant fetchedFrom,
            @Nullable Instant fetchedTo,
            int page,
            int size);

    /**
     * Lists the unmatched-line quarantine for a vendor profile, newest first (ADR-0053 §5).
     *
     * <p>The default — {@code resolved} null or false — is the worklist as it has always been: only
     * open lines. Passing {@code resolved = true} flips the listing to closed lines instead, for
     * auditing what a catalog fix healed. The other filters are optional and null switches each
     * off (#1637 decision 6): {@code search} is a case-insensitive contains-match over the line's
     * EAN, vendor article code and cross-reference code, and the date window binds against
     * {@code fetchedAt}, half-open ({@code fetchedFrom} inclusive, {@code fetchedTo} exclusive).
     *
     * @param vendorProfileId vendor profile identity
     * @param reason          narrow to one quarantine reason
     * @param search          contains-match over the line's identifiers; metacharacters are quoted
     * @param fetchedFrom     inclusive lower bound on {@code fetchedAt}
     * @param fetchedTo       exclusive upper bound on {@code fetchedAt}
     * @param resolved        true lists closed lines instead of the open worklist; null means false
     * @param page            zero-based page index
     * @param size            page size
     * @return a page of quarantined lines
     */
    @NonNull
    PagedResponse<UnmatchedPriceCatalogLineView> listUnmatchedLines(
            @NonNull UUID vendorProfileId,
            @Nullable UnmatchedLineReason reason,
            @Nullable String search,
            @Nullable Instant fetchedFrom,
            @Nullable Instant fetchedTo,
            @Nullable Boolean resolved,
            int page,
            int size);

    /**
     * How fresh the profile's price catalog is (#1637 decision 3): the vendor's newest stated
     * document date and the platform's last retrieval time as separate facts, the open quarantine
     * count, the backend-owned staleness threshold with the {@code stale} verdict it implies, and
     * each PRICE_CATALOG binding's schedule and lease state.
     *
     * @param vendorProfileId vendor profile identity
     * @return the freshness view; a profile that never imported is returned as stale with null
     *         timestamps rather than failing
     * @throws com.positivity.supplier.internal.exception.SupplierConfigurationException
     *         {@code SUPPLIER_UNKNOWN} when no profile carries the id
     */
    @NonNull
    PriceCatalogFreshnessView getFreshness(@NonNull UUID vendorProfileId);

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
