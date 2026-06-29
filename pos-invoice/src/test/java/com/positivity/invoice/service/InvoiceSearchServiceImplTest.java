package com.positivity.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.client.CustomerReferenceClient;
import com.positivity.invoice.internal.client.WorkorderReferenceClient;
import com.positivity.invoice.internal.dto.InvoiceSearchResult;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.service.InvoiceSearchServiceImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for the free-text invoice search service. Covers the customer-name and
 * workorder-number resolution legs (with row enrichment) and the empty-match sentinel path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvoiceSearchServiceImplTest {

    private static final UUID INVOICE_ID = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID WORKORDER_ID = UUID.fromString("22222222-0000-0000-0000-000000000001");
    private static final String PARTY_ID = "party-001";

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private CustomerReferenceClient customerReferenceClient;

    @Mock
    private WorkorderReferenceClient workorderReferenceClient;

    @InjectMocks
    private InvoiceSearchServiceImpl invoiceSearchService;

    private Invoice buildInvoice() {
        Invoice invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        invoice.setInvoiceNumber("INV-2026-1001");
        invoice.setWorkorderId(WORKORDER_ID);
        invoice.setPartyId(PARTY_ID);
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setTotal(new BigDecimal("249.95"));
        invoice.setCreatedAt(Instant.parse("2026-01-15T09:30:00Z"));
        return invoice;
    }

    @Test
    void search_byCustomerName_returnsEnrichedResult() {
        Pageable pageable = PageRequest.of(0, 25);
        Invoice invoice = buildInvoice();

        when(customerReferenceClient.searchIdsByName(eq("Acme"), anyInt())).thenReturn(List.of(PARTY_ID));
        when(workorderReferenceClient.searchIdsByNumber(anyString(), anyInt())).thenReturn(List.of());
        when(invoiceRepository.searchByQuery(any(), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(invoice)));
        when(customerReferenceClient.resolveNames(any())).thenReturn(Map.of(PARTY_ID, "Acme Towing LLC"));
        when(workorderReferenceClient.resolveNumbers(any())).thenReturn(Map.of(WORKORDER_ID, "WO-2026-1001"));

        Page<InvoiceSearchResult> result = invoiceSearchService.search("Acme", pageable);

        assertThat(result.getContent()).hasSize(1);
        InvoiceSearchResult row = result.getContent().get(0);
        assertThat(row.getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(row.getInvoiceNumber()).isEqualTo("INV-2026-1001");
        assertThat(row.getCustomerName()).isEqualTo("Acme Towing LLC");
        assertThat(row.getWorkorderNumber()).isEqualTo("WO-2026-1001");
        assertThat(row.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(row.getTotal()).isEqualByComparingTo("249.95");
    }

    @Test
    void search_noReferenceMatches_passesSentinelsToRepository() {
        Pageable pageable = PageRequest.of(0, 25);

        when(customerReferenceClient.searchIdsByName(anyString(), anyInt())).thenReturn(List.of());
        when(workorderReferenceClient.searchIdsByNumber(anyString(), anyInt())).thenReturn(List.of());
        when(invoiceRepository.searchByQuery(any(), any(), any(), eq(pageable))).thenReturn(new PageImpl<>(List.of()));
        when(customerReferenceClient.resolveNames(any())).thenReturn(Map.of());
        when(workorderReferenceClient.resolveNumbers(any())).thenReturn(Map.of());

        invoiceSearchService.search("INV-2026", pageable);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> customerIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> workorderIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(invoiceRepository)
                .searchByQuery(eq("INV-2026"), customerIdsCaptor.capture(), workorderIdsCaptor.capture(), eq(pageable));

        // Empty reference matches → non-matching sentinels keep the JPQL IN clauses non-empty.
        assertThat(customerIdsCaptor.getValue()).containsExactly("__none__");
        assertThat(workorderIdsCaptor.getValue()).containsExactly(new UUID(0L, 0L));
    }

    @Test
    void search_blankQuery_shortCircuitsWithoutTouchingClientsOrRepository() {
        Page<InvoiceSearchResult> result = invoiceSearchService.search("  ", PageRequest.of(0, 25));

        assertThat(result.getContent()).isEmpty();
        verify(customerReferenceClient, org.mockito.Mockito.never()).searchIdsByName(anyString(), anyInt());
        verify(workorderReferenceClient, org.mockito.Mockito.never()).searchIdsByNumber(anyString(), anyInt());
        verify(invoiceRepository, org.mockito.Mockito.never()).searchByQuery(any(), any(), any(), any());
    }

    @Test
    void search_rowWithNullReferences_doesNotThrowAndOmitsEnrichment() {
        Pageable pageable = PageRequest.of(0, 25);
        Invoice invoice = buildInvoice();
        invoice.setPartyId(null);
        invoice.setWorkorderId(null);

        when(customerReferenceClient.searchIdsByName(anyString(), anyInt())).thenReturn(List.of());
        when(workorderReferenceClient.searchIdsByNumber(anyString(), anyInt())).thenReturn(List.of());
        when(invoiceRepository.searchByQuery(any(), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(invoice)));
        when(customerReferenceClient.resolveNames(any())).thenReturn(Map.of());
        when(workorderReferenceClient.resolveNumbers(any())).thenReturn(Map.of());

        InvoiceSearchResult row =
                invoiceSearchService.search("INV", pageable).getContent().get(0);

        assertThat(row.getCustomerName()).isNull();
        assertThat(row.getWorkorderNumber()).isNull();
    }

    @Test
    void search_escapesLikeWildcardsInQuery() {
        Pageable pageable = PageRequest.of(0, 25);
        when(customerReferenceClient.searchIdsByName(anyString(), anyInt())).thenReturn(List.of());
        when(workorderReferenceClient.searchIdsByNumber(anyString(), anyInt())).thenReturn(List.of());
        when(invoiceRepository.searchByQuery(any(), any(), any(), eq(pageable))).thenReturn(new PageImpl<>(List.of()));
        when(customerReferenceClient.resolveNames(any())).thenReturn(Map.of());
        when(workorderReferenceClient.resolveNumbers(any())).thenReturn(Map.of());

        invoiceSearchService.search("50%_x", pageable);

        verify(invoiceRepository).searchByQuery(eq("50\\%\\_x"), any(), any(), eq(pageable));
    }

    @Test
    void search_byWorkorderNumber_resolvesWorkorderIds() {
        Pageable pageable = PageRequest.of(0, 25);
        Invoice invoice = buildInvoice();

        when(customerReferenceClient.searchIdsByName(anyString(), anyInt())).thenReturn(List.of());
        when(workorderReferenceClient.searchIdsByNumber(eq("WO-2026-1001"), anyInt()))
                .thenReturn(List.of(WORKORDER_ID));
        when(invoiceRepository.searchByQuery(any(), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(invoice)));
        when(customerReferenceClient.resolveNames(any())).thenReturn(Map.of(PARTY_ID, "Acme Towing LLC"));
        when(workorderReferenceClient.resolveNumbers(any())).thenReturn(Map.of(WORKORDER_ID, "WO-2026-1001"));

        invoiceSearchService.search("WO-2026-1001", pageable);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> workorderIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(invoiceRepository).searchByQuery(anyString(), any(), workorderIdsCaptor.capture(), eq(pageable));
        assertThat(workorderIdsCaptor.getValue()).containsExactly(WORKORDER_ID);
    }
}
