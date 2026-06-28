package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.client.CustomerReferenceClient;
import com.positivity.invoice.internal.client.WorkorderReferenceClient;
import com.positivity.invoice.internal.dto.InvoiceSearchResult;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.service.InvoiceSearchService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Free-text invoice search resolving the query against invoice numbers, customer names (via
 * the CRM reference client) and workorder numbers (via the workorder reference client),
 * enriching each row with the resolved customer display name and human workorder number.
 *
 * <p>Mirrors the pos-workorder {@code WorkorderSearchServiceImpl} finder pattern.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvoiceSearchServiceImpl implements InvoiceSearchService {

    /** Sentinel that cannot match a real party id, keeping the JPQL IN clause non-empty. */
    private static final String NO_PARTY_SENTINEL = "__none__";

    /** Sentinel that cannot match a real workorder id, keeping the JPQL IN clause non-empty. */
    private static final UUID NO_WORKORDER_SENTINEL = new UUID(0L, 0L);

    private final InvoiceRepository invoiceRepository;
    private final CustomerReferenceClient customerReferenceClient;
    private final WorkorderReferenceClient workorderReferenceClient;

    @Override
    public @NonNull Page<InvoiceSearchResult> search(@NonNull String q, @NonNull Pageable pageable) {
        // Resolve the query against customer names and workorder numbers in sibling services.
        List<String> nameMatchPartyIds = customerReferenceClient.searchIdsByName(q, 10);
        List<UUID> numberMatchWorkorderIds = workorderReferenceClient.searchIdsByNumber(q, 10);

        List<String> customerIds = nameMatchPartyIds.isEmpty() ? List.of(NO_PARTY_SENTINEL) : nameMatchPartyIds;
        List<UUID> workorderIds =
                numberMatchWorkorderIds.isEmpty() ? List.of(NO_WORKORDER_SENTINEL) : numberMatchWorkorderIds;

        Page<Invoice> page = invoiceRepository.searchByQuery(q, customerIds, workorderIds, pageable);

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

        Map<String, String> customerNames = customerReferenceClient.resolveNames(pagePartyIds);
        Map<UUID, String> workorderNumbers = workorderReferenceClient.resolveNumbers(pageWorkorderIds);

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
}
