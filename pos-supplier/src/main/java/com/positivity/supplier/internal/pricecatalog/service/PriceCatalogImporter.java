package com.positivity.supplier.internal.pricecatalog.service;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.adapter.ediwheelb.EdiwheelB40PricatCodec;
import com.positivity.supplier.internal.adapter.ediwheelb.PricatDecodeException;
import com.positivity.supplier.internal.adapter.ediwheelb.PricatDocument;
import com.positivity.supplier.internal.audit.SupplierCorrelationContext;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpRequest;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierPriceCatalogEntry;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.enums.PriceCatalogMatchMethod;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.AdapterResolution;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedPartyAccounts;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

/**
 * Runs one PRICAT import end to end (CAP-318 #1224, ADR-0053).
 *
 * <h2>Shape of a run</h2>
 *
 * Resolve the binding and buyer account, fetch through the shared base client, decode with the
 * registered codec, deduplicate within the document, resolve each line against pos-catalog, then
 * hand the result to {@link PriceCatalogStagingWriter}, which commits staging and the events
 * together.
 *
 * <p>Network work deliberately happens <em>outside</em> the transaction. A vendor catalog fetch
 * plus tens of thousands of catalog lookups would otherwise hold a database transaction open for
 * minutes, and one lookup timeout would roll back staging that was already correct.
 *
 * <h2>A bad fetch never destroys good data</h2>
 *
 * Staging is append-only and nothing here deletes or supersedes prior entries. A failed exchange,
 * an undecodable document, or a document with zero lines records a terminal import row and stops —
 * yesterday's prices remain the latest applicable prices.
 *
 * <h2>Every line is accounted for</h2>
 *
 * A line becomes either a staged entry with a matched product or a quarantine row with a reason
 * (ADR-0053 §5). The writer asserts {@code matched + unmatched + duplicate == fetched} before
 * marking the import complete, so a silent drop fails the import rather than under-reporting it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceCatalogImporter {

    /** ADR-0053 §7 default; validate against the first Michelin sandbox pull and record it there. */
    public static final int DEFAULT_CHUNK_SIZE = 500;

    private final SupplierProfileResolver profileResolver;
    private final AdapterRegistry adapterRegistry;
    private final SupplierBaseClient baseClient;
    private final ProductCodeResolver productCodeResolver;
    private final PriceCatalogStagingWriter stagingWriter;
    private final Clock clock;

    @Value("${pos.supplier.pricat.chunk-size:" + DEFAULT_CHUNK_SIZE + "}")
    private int defaultChunkSize;

    /**
     * Fetches and stages the vendor's current price catalog.
     *
     * @param supplierRef vendor profile alias to import for
     * @return the persisted import row; a failed or empty fetch is a terminal row, not an exception
     * @throws SupplierConfigurationException when the profile, binding, codec or billing account is
     *                                        not configured — a deployment defect, surfaced loudly
     *                                        rather than absorbed into a failed import
     */
    @NonNull
    public PriceCatalogImportEntity runImport(@NonNull SupplierRef supplierRef) {
        ResolvedBinding binding = profileResolver.resolveBinding(supplierRef, SupplierCapability.PRICE_CATALOG);
        EdiwheelB40PricatCodec codec = resolveCodec(binding);
        ResolvedPartyAccounts accounts = profileResolver.resolvePartyContext(supplierRef, null);
        SupplierAccountEntity billing = accounts.billing();

        UUID importManifestId = UUIDv7Generator.generate();
        String correlationId = SupplierCorrelationContext.currentOrGenerate();
        Instant fetchedAt = Instant.now(clock);

        PartyContext partyContext = new PartyContext(billing.getAccountNumber(), billing.getAgencyCode(), null);
        SupplierRequestSpec spec = codec.buildRequest(partyContext, null);
        SupplierHttpResponse response = baseClient.exchange(toHttpRequest(binding, spec));

        if (!response.isSuccess()) {
            return stagingWriter.persistFailure(
                    importManifestId,
                    binding,
                    billing,
                    fetchedAt,
                    correlationId,
                    "vendor exchange failed: " + response.outcome()
                            + (response.failureDetail() == null ? "" : " — " + response.failureDetail()));
        }

        PricatDocument document;
        try {
            document = codec.decode(response.body(), importManifestId);
        } catch (PricatDecodeException e) {
            return stagingWriter.persistFailure(
                    importManifestId, binding, billing, fetchedAt, correlationId, e.getMessage());
        }

        PreparedImport prepared = prepare(document, importManifestId);
        int chunkSize = binding.binding().getEventChunkSize() == null
                ? defaultChunkSize
                : binding.binding().getEventChunkSize();

        return stagingWriter.commit(
                prepared, document, binding, billing, importManifestId, correlationId, fetchedAt, chunkSize);
    }

    // ── Preparation: dedupe and match, both outside any transaction ─────────────────────────

    PreparedImport prepare(PricatDocument document, UUID importManifestId) {
        List<MatchedLine> matched = new ArrayList<>();
        List<QuarantinedLine> quarantined = new ArrayList<>();
        int duplicates = 0;

        // Lines the codec could not decode are quarantined verbatim rather than dropped.
        for (PricatDocument.RejectedLine rejected : document.rejectedLines()) {
            quarantined.add(new QuarantinedLine(
                    null,
                    rejected.positionNumber(),
                    rejected.articleEan(),
                    rejected.supplierArticleCode(),
                    rejected.xReferenceCode(),
                    UnmatchedLineReason.MALFORMED_LINE,
                    rejected.detail()));
        }

        Set<String> seenIdentities = new HashSet<>();
        for (SupplierPriceCatalogEntry entry : document.entries()) {
            String identity = identityKey(entry);
            if (!seenIdentities.add(identity)) {
                duplicates++;
                log.info(
                        "PRICAT duplicate line: import={} identity={} position={} — first occurrence kept",
                        importManifestId,
                        identity,
                        entry.positionNumber());
                quarantined.add(new QuarantinedLine(
                        entry,
                        entry.positionNumber(),
                        entry.articleEan(),
                        entry.supplierArticleCode(),
                        entry.xReferenceCode(),
                        UnmatchedLineReason.DUPLICATE_LINE,
                        "identifier " + identity + " already appeared in this document"));
                continue;
            }

            MatchOutcome outcome = match(entry);
            if (outcome.productId() != null && outcome.method() != null) {
                matched.add(new MatchedLine(entry, outcome.productId(), outcome.method()));
            } else {
                quarantined.add(new QuarantinedLine(
                        entry,
                        entry.positionNumber(),
                        entry.articleEan(),
                        entry.supplierArticleCode(),
                        entry.xReferenceCode(),
                        outcome.reason() == null ? UnmatchedLineReason.NO_CATALOG_MATCH : outcome.reason(),
                        outcome.detail()));
            }
        }
        return new PreparedImport(matched, quarantined, duplicates);
    }

    /**
     * Deterministic match order (ADR-0053 §5): exact EAN, then the cross-reference code as a UPC.
     *
     * <p>Manufacturer-part matching is the ADR's third step and needs a supplier-to-manufacturer
     * mapping that no vendor profile carries yet; when it arrives it is a third exact step here,
     * never a fallback that guesses.
     */
    MatchOutcome match(SupplierPriceCatalogEntry entry) {
        MatchOutcome byEan = lookup(entry.articleEan(), "EAN", PriceCatalogMatchMethod.EAN);
        if (byEan.productId() != null || byEan.reason() == UnmatchedLineReason.AMBIGUOUS_CATALOG_MATCH) {
            return byEan;
        }
        MatchOutcome byXref = lookup(entry.xReferenceCode(), "UPC", PriceCatalogMatchMethod.UPC_XREFERENCE);
        if (byXref.productId() != null || byXref.reason() == UnmatchedLineReason.AMBIGUOUS_CATALOG_MATCH) {
            return byXref;
        }
        // An unanswerable replica outranks a plain miss: those lines are worth retrying later,
        // whereas a miss needs catalog work. Collapsing the two would strand recoverable lines.
        if (byEan.reason() == UnmatchedLineReason.CATALOG_UNAVAILABLE
                || byXref.reason() == UnmatchedLineReason.CATALOG_UNAVAILABLE) {
            return new MatchOutcome(
                    null, null, UnmatchedLineReason.CATALOG_UNAVAILABLE, "product-code replica unavailable");
        }
        if (isBlank(entry.articleEan()) && isBlank(entry.xReferenceCode())) {
            return new MatchOutcome(
                    null,
                    null,
                    UnmatchedLineReason.NO_IDENTIFIER,
                    "line carries neither an EAN nor a cross-reference code");
        }
        return new MatchOutcome(
                null, null, UnmatchedLineReason.NO_CATALOG_MATCH, "no catalog product carries the line's identifiers");
    }

    private MatchOutcome lookup(@Nullable String code, String codeType, PriceCatalogMatchMethod method) {
        if (isBlank(code)) {
            return new MatchOutcome(null, null, UnmatchedLineReason.NO_IDENTIFIER, null);
        }
        ProductCodeResolver.Resolution resolution = productCodeResolver.resolve(codeType, code);
        return switch (resolution) {
            case ProductCodeResolver.Resolution.Matched m -> new MatchOutcome(m.productId(), method, null, null);
            case ProductCodeResolver.Resolution.NotFound ignored ->
                new MatchOutcome(null, null, UnmatchedLineReason.NO_CATALOG_MATCH, null);
            case ProductCodeResolver.Resolution.Ambiguous a ->
                new MatchOutcome(null, null, UnmatchedLineReason.AMBIGUOUS_CATALOG_MATCH, a.detail());
            case ProductCodeResolver.Resolution.Unavailable u ->
                new MatchOutcome(null, null, UnmatchedLineReason.CATALOG_UNAVAILABLE, u.detail());
        };
    }

    /**
     * Turns a codec's transport-free request spec into a transport request against the resolved
     * binding. The join lives here rather than in the codec so the adapter package stays free of
     * transport and configuration types (ADR-0051 §2).
     */
    private SupplierHttpRequest toHttpRequest(ResolvedBinding binding, SupplierRequestSpec spec) {
        return new SupplierHttpRequest(
                binding,
                HttpMethod.valueOf(spec.method()),
                spec.pathSuffix(),
                spec.queryParams(),
                spec.body(),
                spec.contentType(),
                spec.accept(),
                spec.idempotent());
    }

    private EdiwheelB40PricatCodec resolveCodec(ResolvedBinding binding) {
        AdapterResolution resolution =
                adapterRegistry.resolve(SupplierCapability.PRICE_CATALOG, binding.family(), binding.version());
        if (resolution instanceof AdapterResolution.Resolved resolved
                && resolved.codec() instanceof EdiwheelB40PricatCodec codec) {
            return codec;
        }
        throw new SupplierConfigurationException(
                SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED,
                "No PRICAT codec registered for " + binding.family() + " "
                        + binding.version().value());
    }

    /**
     * Identity of a line within one document, for deduplication only. EAN first, then the
     * cross-reference code, then the supplier code — the supplier code appears here solely to tell
     * two otherwise-identifierless lines apart, and never matches a product (ADR-0053 §5).
     */
    private String identityKey(SupplierPriceCatalogEntry entry) {
        if (!isBlank(entry.articleEan())) {
            return "ean:" + entry.articleEan();
        }
        if (!isBlank(entry.xReferenceCode())) {
            return "xref:" + entry.xReferenceCode();
        }
        return "supplier:" + entry.supplierArticleCode();
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    /** A line resolved to a catalog product. */
    public record MatchedLine(SupplierPriceCatalogEntry entry, UUID productId, PriceCatalogMatchMethod method) {}

    /** A line bound for quarantine; {@code entry} is null when the line never decoded. */
    public record QuarantinedLine(
            @Nullable SupplierPriceCatalogEntry entry,
            @Nullable Integer positionNumber,
            @Nullable String articleEan,
            @Nullable String supplierArticleCode,
            @Nullable String xReferenceCode,
            UnmatchedLineReason reason,
            @Nullable String detail) {}

    /** Result of one catalog lookup attempt. */
    record MatchOutcome(
            @Nullable UUID productId,
            @Nullable PriceCatalogMatchMethod method,
            @Nullable UnmatchedLineReason reason,
            @Nullable String detail) {}

    /** Everything decided before the staging transaction opens. */
    public record PreparedImport(List<MatchedLine> matched, List<QuarantinedLine> quarantined, int duplicates) {}
}
