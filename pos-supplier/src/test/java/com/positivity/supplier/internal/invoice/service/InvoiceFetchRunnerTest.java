package com.positivity.supplier.internal.invoice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.exception.InvoiceFetchException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Fetching one window (CAP-321 #1343).
 *
 * <p>The request is checked before anything is resolved or sent. An operator's typo should come
 * back as the request problem it is, not as a vendor failure or a 500 — and it should certainly not
 * reach the vendor first.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InvoiceFetchRunner — one window (#1343)")
class InvoiceFetchRunnerTest {

    @Mock
    private SupplierProfileResolver profileResolver;

    @Mock
    private AdapterRegistry adapterRegistry;

    @Mock
    private SupplierBaseClient baseClient;

    @Mock
    private InvoiceImporter importer;

    private InvoiceFetchRunner runner;

    @BeforeEach
    void setUp() {
        runner = new InvoiceFetchRunner(profileResolver, adapterRegistry, baseClient, importer);
    }

    @Test
    @DisplayName("a window ending before it begins is refused before anything is resolved or sent")
    void invertedWindowIsRefusedUpFront() {
        assertThatThrownBy(() -> runner.fetchWindow(
                        new SupplierRef("michelin-de"), LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 1)))
                .isInstanceOf(InvoiceFetchException.class)
                .hasMessageContaining("ends before it begins");

        // Not merely a different exception type: the vendor is never called, and no profile lookup
        // happens either, so a typo cannot surface as a configuration error about the wrong thing.
        verify(profileResolver, never()).resolveBinding(any(), any());
        verify(baseClient, never()).exchange(any());
        verify(importer, never()).importInvoices(any(), any(), any());
    }
}
