package com.positivity.invoice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.internal.dto.InvoiceSearchResult;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.service.InvoiceSearchService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller-layer unit tests for {@link InvoiceSearchController}. Uses standalone MockMvc
 * (no Spring context); authority enforcement is validated at the security layer. Asserts the
 * query is trimmed and delegated, and the paginated body is serialized.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceSearchControllerTest {

    private static final UUID INVOICE_ID = UUID.fromString("11111111-0000-0000-0000-000000000001");

    @Mock
    private InvoiceSearchService invoiceSearchService;

    @InjectMocks
    private InvoiceSearchController invoiceSearchController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(invoiceSearchController)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    private InvoiceSearchResult row() {
        return InvoiceSearchResult.builder()
                .invoiceId(INVOICE_ID)
                .invoiceNumber("INV-2026-1001")
                .customerName("Acme Towing LLC")
                .workorderNumber("WO-2026-1001")
                .status(InvoiceStatus.DRAFT)
                .total(new BigDecimal("249.95"))
                .createdAt(Instant.parse("2026-01-15T09:30:00Z"))
                .build();
    }

    @Test
    void search_returnsPagedResults() throws Exception {
        when(invoiceSearchService.search(eq("Acme"), any()))
                .thenReturn(new PageImpl<>(List.of(row()), PageRequest.of(0, 25), 1));

        mockMvc.perform(get("/v1/invoices/search").param("q", "Acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].invoiceId").value(INVOICE_ID.toString()))
                .andExpect(jsonPath("$.content[0].invoiceNumber").value("INV-2026-1001"))
                .andExpect(jsonPath("$.content[0].customerName").value("Acme Towing LLC"))
                .andExpect(jsonPath("$.content[0].workorderNumber").value("WO-2026-1001"));
    }

    @Test
    void search_trimsQueryBeforeDelegating() throws Exception {
        when(invoiceSearchService.search(eq("Acme"), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

        mockMvc.perform(get("/v1/invoices/search").param("q", "  Acme  ")).andExpect(status().isOk());

        verify(invoiceSearchService).search(eq("Acme"), any());
    }

    @Test
    void search_missingQuery_delegatesEmptyString() throws Exception {
        when(invoiceSearchService.search(eq(""), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

        mockMvc.perform(get("/v1/invoices/search")).andExpect(status().isOk());

        verify(invoiceSearchService).search(eq(""), any());
    }

    @Test
    void search_clampsPageSizeToMax_preservingPageIndexAndSort() throws Exception {
        when(invoiceSearchService.search(eq("Acme"), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 50), 0));

        mockMvc.perform(get("/v1/invoices/search")
                        .param("q", "Acme")
                        .param("page", "2")
                        .param("size", "200")
                        .param("sort", "invoiceNumber,asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(invoiceSearchService).search(eq("Acme"), pageableCaptor.capture());
        Pageable delegated = pageableCaptor.getValue();
        assertThat(delegated.getPageSize()).isEqualTo(50);
        assertThat(delegated.getPageNumber()).isEqualTo(2);
        assertThat(delegated.getSort().getOrderFor("invoiceNumber"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.ASC);
    }
}
