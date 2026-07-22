package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.client.CatalogClient;
import com.positivity.warranty.internal.dto.CandidateLine;
import com.positivity.warranty.internal.entity.ExtInvoiceLineReplica;
import com.positivity.warranty.internal.entity.ExtInvoiceReplica;
import com.positivity.warranty.internal.entity.ExtWorkorderLineReplica;
import com.positivity.warranty.internal.entity.ExtWorkorderReplica;
import com.positivity.warranty.internal.enums.LineSourceType;
import com.positivity.warranty.internal.repository.ExtInvoiceLineReplicaRepository;
import com.positivity.warranty.internal.repository.ExtInvoiceReplicaRepository;
import com.positivity.warranty.internal.repository.ExtWorkorderLineReplicaRepository;
import com.positivity.warranty.internal.repository.ExtWorkorderReplicaRepository;
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
 * pos-workorder part/service lines, both read from event-fed {@code ext_*} replicas (ADR-0044 §6,
 * #924). Filtering is best-effort — workorder parts match by {@code productEntityId} directly;
 * sources without a product reference (invoice lines, service lines) match by description against
 * the SKU / catalog product name. Each source is isolated: a read failure on one still returns the
 * other's results (partial results are fine — the clerk can fall back to manual origin entry).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateLineServiceImpl implements CandidateLineService {

    /** Upper bound on workorder fan-out per search, to keep the endpoint snappy. */
    private static final int MAX_WORKORDERS = 25;

    private final ExtInvoiceReplicaRepository extInvoiceReplicaRepository;
    private final ExtInvoiceLineReplicaRepository extInvoiceLineReplicaRepository;
    private final ExtWorkorderReplicaRepository extWorkorderReplicaRepository;
    private final ExtWorkorderLineReplicaRepository extWorkorderLineReplicaRepository;
    private final CatalogClient catalogClient;

    @Override
    @NonNull
    public List<CandidateLine> findCandidateLines(
            @NonNull UUID customerId, @Nullable UUID vehicleId, @Nullable String sku, @Nullable UUID productEntityId) {
        boolean filtered = sku != null || productEntityId != null;
        CatalogClient.ProductInfo product = resolveProduct(productEntityId);
        Set<String> textTokens = textTokens(sku, product);

        List<CandidateLine> results = new ArrayList<>();
        results.addAll(invoiceCandidates(customerId, filtered, textTokens));
        results.addAll(workorderCandidates(customerId, vehicleId, filtered, productEntityId, textTokens, product));
        return results;
    }

    // -----------------------------------------------------------------------------------------
    // pos-invoice (ext_invoice replica)
    // -----------------------------------------------------------------------------------------

    private List<CandidateLine> invoiceCandidates(UUID customerId, boolean filtered, Set<String> textTokens) {
        List<CandidateLine> results = new ArrayList<>();
        try {
            for (ExtInvoiceReplica invoice : extInvoiceReplicaRepository.findByPartyId(customerId.toString())) {
                for (ExtInvoiceLineReplica line :
                        extInvoiceLineReplicaRepository.findByInvoiceId(invoice.getInvoiceId())) {
                    if (filtered && !matchesText(line.getDescription(), textTokens)) {
                        continue;
                    }
                    results.add(new CandidateLine(
                            LineSourceType.INVOICE_LINE,
                            invoice.getInvoiceId(),
                            line.getInvoiceItemId(),
                            invoice.getInvoiceNumber(),
                            null,
                            null,
                            line.getDescription(),
                            line.getQuantity(),
                            line.getUnitPrice(),
                            line.getAmount(),
                            invoice.getInvoiceCreatedAt(),
                            null));
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Candidate-line invoice search degraded for customerId={}: {}", customerId, ex.getMessage());
        }
        return results;
    }

    // -----------------------------------------------------------------------------------------
    // pos-workorder (ext_workorder replica)
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
            List<ExtWorkorderReplica> workorders = vehicleId == null
                    ? extWorkorderReplicaRepository.findByCustomerId(customerId)
                    : extWorkorderReplicaRepository.findByCustomerIdAndVehicleId(customerId, vehicleId);
            for (ExtWorkorderReplica workorder :
                    workorders.stream().limit(MAX_WORKORDERS).toList()) {
                for (ExtWorkorderLineReplica line :
                        extWorkorderLineReplicaRepository.findByWorkorderId(workorder.getWorkorderId())) {
                    collectWorkorderLine(workorder, line, filtered, productEntityId, textTokens, product, results);
                }
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

    private static void collectWorkorderLine(
            ExtWorkorderReplica workorder,
            ExtWorkorderLineReplica line,
            boolean filtered,
            @Nullable UUID productEntityId,
            Set<String> textTokens,
            CatalogClient.@Nullable ProductInfo product,
            List<CandidateLine> results) {
        if ("PART".equals(line.getLineKind())) {
            if (filtered && !matchesPart(line, productEntityId, textTokens)) {
                return;
            }
            String partSku = product != null && product.id().equals(line.getProductEntityId()) ? product.sku() : null;
            results.add(new CandidateLine(
                    LineSourceType.WORKORDER_PART,
                    workorder.getWorkorderId(),
                    line.getWorkorderLineId(),
                    workorder.getWorkorderNumber(),
                    line.getProductEntityId(),
                    partSku,
                    line.getDescription(),
                    line.getQuantity(),
                    line.getUnitPrice(),
                    line.getLineTotal(),
                    workorder.getWorkorderCreatedAt(),
                    line.getPhotoEvidenceUrl()));
        } else {
            // Service lines carry no product reference — a product filter matches by text only.
            if (filtered && !matchesText(line.getDescription(), textTokens)) {
                return;
            }
            results.add(new CandidateLine(
                    LineSourceType.WORKORDER_SERVICE,
                    workorder.getWorkorderId(),
                    line.getWorkorderLineId(),
                    workorder.getWorkorderNumber(),
                    null,
                    null,
                    line.getDescription(),
                    line.getQuantity(),
                    line.getUnitPrice(),
                    line.getLineTotal(),
                    workorder.getWorkorderCreatedAt(),
                    null));
        }
    }

    // -----------------------------------------------------------------------------------------
    // Filter helpers
    // -----------------------------------------------------------------------------------------

    private static boolean matchesPart(
            ExtWorkorderLineReplica part, @Nullable UUID productEntityId, Set<String> textTokens) {
        if (productEntityId != null && productEntityId.equals(part.getProductEntityId())) {
            return true;
        }
        return matchesText(part.getDescription(), textTokens);
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
