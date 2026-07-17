package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.client.CatalogClient;
import com.positivity.warranty.internal.client.InvoiceClient;
import com.positivity.warranty.internal.client.WorkorderClient;
import com.positivity.warranty.internal.dto.CandidateLine;
import com.positivity.warranty.internal.enums.LineSourceType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Cross-service origin-line search (PRD §7 step 2): combines pos-invoice line search with
 * pos-workorder part/service lines. Filtering is best-effort — workorder parts match by
 * {@code productEntityId} directly; sources without a product reference (invoice lines,
 * service lines) match by description against the SKU / catalog product name. Each callee is
 * isolated: if one is down the other's results are still returned (partial results are fine —
 * the clerk can always fall back to manual origin entry).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateLineServiceImpl implements CandidateLineService {

    /** Upper bound on workorder detail fan-out per search, to keep the endpoint snappy. */
    private static final int MAX_WORKORDER_DETAILS = 25;

    private final InvoiceClient invoiceClient;
    private final WorkorderClient workorderClient;
    private final CatalogClient catalogClient;

    @Override
    @NonNull
    public List<CandidateLine> findCandidateLines(
            @NonNull UUID customerId, @Nullable UUID vehicleId, @Nullable String sku, @Nullable UUID productEntityId) {
        boolean filtered = sku != null || productEntityId != null;
        CatalogClient.ProductInfo product = resolveProduct(productEntityId);
        Set<String> textTokens = textTokens(sku, product);

        List<CandidateLine> results = new ArrayList<>();
        results.addAll(invoiceCandidates(customerId, sku, filtered, textTokens));
        results.addAll(workorderCandidates(customerId, vehicleId, filtered, productEntityId, textTokens, product));
        return results;
    }

    // -----------------------------------------------------------------------------------------
    // pos-invoice
    // -----------------------------------------------------------------------------------------

    private List<CandidateLine> invoiceCandidates(
            UUID customerId, @Nullable String sku, boolean filtered, Set<String> textTokens) {
        List<CandidateLine> results = new ArrayList<>();
        try {
            // The SKU narrows the result server-side (PRD §9.4 party+SKU); the token match below
            // stays as a secondary filter for catalog-name tokens.
            for (InvoiceClient.InvoiceLine line : invoiceClient.searchInvoiceLines(customerId, sku)) {
                if (filtered && !matchesText(line.description(), textTokens)) {
                    continue;
                }
                results.add(new CandidateLine(
                        LineSourceType.INVOICE_LINE,
                        line.invoiceId(),
                        line.invoiceItemId(),
                        line.invoiceNumber(),
                        null,
                        null,
                        line.description(),
                        line.quantity(),
                        line.unitPrice(),
                        line.amount(),
                        line.invoiceCreatedAt(),
                        null));
            }
        } catch (RuntimeException ex) {
            log.warn("Candidate-line invoice search degraded for customerId={}: {}", customerId, ex.getMessage());
        }
        return results;
    }

    // -----------------------------------------------------------------------------------------
    // pos-workorder
    // -----------------------------------------------------------------------------------------

    private List<CandidateLine> workorderCandidates(
            UUID customerId,
            @Nullable UUID vehicleId,
            boolean filtered,
            @Nullable UUID productEntityId,
            Set<String> textTokens,
            CatalogClient.@Nullable ProductInfo product) {
        List<CandidateLine> results = new ArrayList<>();
        try {
            List<WorkorderClient.WorkorderSummary> summaries = workorderClient.searchWorkorders(customerId, vehicleId);
            for (WorkorderClient.WorkorderSummary summary :
                    summaries.stream().limit(MAX_WORKORDER_DETAILS).toList()) {
                workorderClient
                        .getWorkorderDetail(summary.workorderId())
                        .ifPresent(detail -> collectWorkorderLines(
                                detail, summary, filtered, productEntityId, textTokens, product, results));
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "Candidate-line workorder search degraded for customerId={} vehicleId={}: {}",
                    customerId,
                    vehicleId,
                    ex.getMessage());
        }
        return results;
    }

    private static void collectWorkorderLines(
            WorkorderClient.WorkorderDetail detail,
            WorkorderClient.WorkorderSummary summary,
            boolean filtered,
            @Nullable UUID productEntityId,
            Set<String> textTokens,
            CatalogClient.@Nullable ProductInfo product,
            List<CandidateLine> results) {
        if (detail.parts() != null) {
            for (WorkorderClient.PartLine part : detail.parts()) {
                if (filtered && !matchesPart(part, productEntityId, textTokens)) {
                    continue;
                }
                String partSku = product != null && product.id().equals(part.productEntityId()) ? product.sku() : null;
                results.add(new CandidateLine(
                        LineSourceType.WORKORDER_PART,
                        detail.workorderId(),
                        part.id(),
                        detail.workorderNumber(),
                        part.productEntityId(),
                        partSku,
                        part.description(),
                        part.quantity(),
                        part.unitPrice(),
                        part.lineTotal(),
                        summary.createdAt(),
                        part.photoEvidenceUrl()));
            }
        }
        if (detail.services() != null) {
            for (WorkorderClient.ServiceLine service : detail.services()) {
                // Service lines carry no product reference — a product filter matches by text only.
                if (filtered && !matchesText(service.description(), textTokens)) {
                    continue;
                }
                results.add(new CandidateLine(
                        LineSourceType.WORKORDER_SERVICE,
                        detail.workorderId(),
                        service.id(),
                        detail.workorderNumber(),
                        null,
                        null,
                        service.description(),
                        service.laborHours(),
                        service.laborRate(),
                        service.lineTotal(),
                        summary.createdAt(),
                        null));
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Filter helpers
    // -----------------------------------------------------------------------------------------

    private static boolean matchesPart(
            WorkorderClient.PartLine part, @Nullable UUID productEntityId, Set<String> textTokens) {
        if (productEntityId != null && productEntityId.equals(part.productEntityId())) {
            return true;
        }
        return matchesText(part.description(), textTokens);
    }

    /** Case-insensitive containment of any token in the description. */
    private static boolean matchesText(@Nullable String description, Set<String> tokens) {
        if (description == null || tokens.isEmpty()) {
            return false;
        }
        String haystack = description.toLowerCase(Locale.ROOT);
        return tokens.stream().anyMatch(haystack::contains);
    }

    private CatalogClient.@Nullable ProductInfo resolveProduct(@Nullable UUID productEntityId) {
        if (productEntityId == null) {
            return null;
        }
        return catalogClient.getProduct(productEntityId).orElse(null);
    }

    /** Tokens used for description matching: the requested SKU plus the catalog SKU and name. */
    private static Set<String> textTokens(@Nullable String sku, CatalogClient.@Nullable ProductInfo product) {
        Set<String> tokens = new LinkedHashSet<>();
        if (sku != null && !sku.isBlank()) {
            tokens.add(sku.toLowerCase(Locale.ROOT).trim());
        }
        if (product != null) {
            if (product.sku() != null && !product.sku().isBlank()) {
                tokens.add(product.sku().toLowerCase(Locale.ROOT).trim());
            }
            if (product.name() != null && !product.name().isBlank()) {
                tokens.add(product.name().toLowerCase(Locale.ROOT).trim());
            }
        }
        return tokens;
    }
}
