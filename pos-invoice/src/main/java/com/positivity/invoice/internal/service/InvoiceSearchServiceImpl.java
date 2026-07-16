package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.dto.InvoiceLineSearchResult;
import com.positivity.invoice.internal.dto.InvoiceSearchResult;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.InvoiceItem;
import com.positivity.invoice.internal.repository.InvoiceItemRepository;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.service.InvoiceSearchService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Free-text invoice search resolving the query against invoice numbers, customer names (via
 * the local ext_customer_party replica) and workorder numbers (via the workorder reference client),
 * enriching each row with the resolved customer display name and human workorder number.
 *
 * <p>Mirrors the pos-workorder {@code WorkorderSearchServiceImpl} finder pattern.
 *
 * <p>No method-level transaction is declared: the search is a single repository read whose
 * mapping touches only scalar columns (no lazy associations), and the post-query workorder
 * enrichment performs a remote HTTP call — running it inside an open transaction would hold a
 * pooled DB connection for the duration of the remote call.
 */
@Service
@RequiredArgsConstructor
public class InvoiceSearchServiceImpl implements InvoiceSearchService {

    /** Sentinel that cannot match a real party id, keeping the JPQL IN clause non-empty. */
    private static final String NO_PARTY_SENTINEL = "__none__";

    /** Sentinel that cannot match a real workorder id, keeping the JPQL IN clause non-empty. */
    private static final UUID NO_WORKORDER_SENTINEL = new UUID(0L, 0L);

    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final CustomerReferenceService customerReferenceService;
    private final WorkorderReferenceService workorderReferenceService;

    @Override
    public @NonNull Page<InvoiceSearchResult> search(@NonNull String q, @NonNull Pageable pageable) {
        // A blank query would degenerate to LIKE '%%' (full-table scan in undefined order); a
        // finder requires an actual term, so short-circuit to an empty page.
        if (!StringUtils.hasText(q)) {
            return Page.empty(pageable);
        }

        // Resolve the query against customer names and workorder numbers in sibling services.
        List<String> nameMatchPartyIds = customerReferenceService.searchIdsByName(q, 10);
        List<UUID> numberMatchWorkorderIds = workorderReferenceService.searchIdsByNumber(q, 10);

        List<String> customerIds = nameMatchPartyIds.isEmpty() ? List.of(NO_PARTY_SENTINEL) : nameMatchPartyIds;
        List<UUID> workorderIds =
                numberMatchWorkorderIds.isEmpty() ? List.of(NO_WORKORDER_SENTINEL) : numberMatchWorkorderIds;

        // Escape LIKE wildcards so a query containing % or _ matches literally.
        Page<Invoice> page = invoiceRepository.searchByQuery(escapeLike(q), customerIds, workorderIds, pageable);

        // Enrich each row with the resolved customer display name and human workorder number.
        List<String> pagePartyIds = page.getContent().stream()
                .map(Invoice::getPartyId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<UUID> pageWorkorderIds = page.getContent().stream()
                .map(Invoice::getWorkorderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<String, String> customerNames = customerReferenceService.resolveNames(pagePartyIds);
        Map<UUID, String> workorderNumbers = workorderReferenceService.resolveNumbers(pageWorkorderIds);

        return page.map(invoice -> InvoiceSearchResult.builder()
                .invoiceId(invoice.getId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .customerName(invoice.getPartyId() != null ? customerNames.get(invoice.getPartyId()) : null)
                .workorderId(invoice.getWorkorderId())
                .workorderNumber(
                        invoice.getWorkorderId() != null ? workorderNumbers.get(invoice.getWorkorderId()) : null)
                .status(invoice.getStatus())
                .total(invoice.getTotal())
                .createdAt(invoice.getCreatedAt())
                .build());
    }

    /**
     * Hard server-side bound on the line search: newest lines survive truncation (the query
     * orders by invoice creation time descending), keeping a high-history fleet party from
     * join-fetching its entire billing history into the heap per call.
     */
    static final int MAX_LINE_RESULTS = 200;

    @Override
    public @NonNull List<InvoiceLineSearchResult> searchLinesByParty(@NonNull UUID partyId, @Nullable String q) {
        // Invoices store the party id as a string column; UUID party ids are persisted in
        // canonical lowercase form (UUID.toString()).
        String descriptionFilter = StringUtils.hasText(q) ? escapeLike(q.trim()) : null;
        return invoiceItemRepository
                .findByInvoicePartyId(partyId.toString(), descriptionFilter, PageRequest.of(0, MAX_LINE_RESULTS))
                .stream()
                .map(InvoiceSearchServiceImpl::toLineResult)
                .toList();
    }

    private static @NonNull InvoiceLineSearchResult toLineResult(@NonNull InvoiceItem item) {
        Invoice invoice = item.getInvoice();
        return InvoiceLineSearchResult.builder()
                .invoiceId(invoice.getId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .invoiceItemId(item.getId())
                .description(item.getDescription())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .amount(item.getLineTotal())
                .workorderItemId(item.getWorkorderItemId())
                .itemType(item.getType())
                .invoiceStatus(
                        invoice.getStatus() == null ? null : invoice.getStatus().name())
                .invoiceCreatedAt(invoice.getCreatedAt())
                .build();
    }

    /**
     * Escape SQL LIKE metacharacters ({@code \}, {@code %}, {@code _}) so a user query is
     * matched as a literal substring. Pairs with the {@code ESCAPE '\'} clause in the repository
     * query.
     */
    private static @NonNull String escapeLike(@NonNull String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
