package com.positivity.supplier.internal.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.adapter.ediwheelb3.EdiwheelB33InvoiceCodec;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.exception.InvoiceFetchException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.AdapterResolution;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.spi.ExchangeOutcome;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
        verify(importer, never()).importInvoices(any(), any());
    }

    @Test
    @DisplayName("AC 9: the importer is told the binding's protocol family and the exchange that returned the window")
    void passesChannelAndExchangeIdToTheImporter() {
        UUID profileId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        UUID exchangeId = UUID.randomUUID();
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(profileId);
        profile.setVendorId(vendorId);
        profile.setSupplierRef("michelin-de");
        SupplierEndpointBindingEntity binding = new SupplierEndpointBindingEntity();
        binding.setVendorProfileId(profileId);
        ResolvedBinding resolved = new ResolvedBinding(
                profile,
                binding,
                new SupplierAuthConfigEntity(),
                SupplierCapability.INVOICE_FETCH,
                ProtocolFamily.EDIWHEEL_B,
                new ProtocolVersion("B3_3"));
        EdiwheelB33InvoiceCodec codec = mock(EdiwheelB33InvoiceCodec.class);
        SupplierAccountEntity billing = new SupplierAccountEntity();
        billing.setAccountNumber("0000012345");
        when(profileResolver.resolveBinding(any(), eq(SupplierCapability.INVOICE_FETCH)))
                .thenReturn(resolved);
        when(profileResolver.resolvePartyContext(any(), any()))
                .thenReturn(new SupplierProfileResolver.ResolvedPartyAccounts(billing, null));
        when(adapterRegistry.resolve(any(), any(), any())).thenReturn(new AdapterResolution.Resolved(codec));
        when(codec.buildRequest(any(), any(), any()))
                .thenReturn(new SupplierRequestSpec("GET", "/invoices", Map.of(), null, null, null, true, Map.of()));
        when(codec.decode(any())).thenReturn(List.of());
        when(baseClient.exchange(any()))
                .thenReturn(new SupplierHttpResponse(
                        ExchangeOutcome.OK, 200, "<doc/>", "c-1", 1, Duration.ZERO, null, Map.of(), exchangeId));

        runner.fetchWindow(new SupplierRef("michelin-de"), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15));

        ArgumentCaptor<InvoiceImporter.InvoiceSource> source =
                ArgumentCaptor.forClass(InvoiceImporter.InvoiceSource.class);
        verify(importer).importInvoices(source.capture(), any());
        assertThat(source.getValue().channel()).isEqualTo("EDIWHEEL_B");
        assertThat(source.getValue().exchangeId()).isEqualTo(exchangeId);
        assertThat(source.getValue().vendorId()).isEqualTo(vendorId);
        assertThat(source.getValue().vendorProfileId()).isEqualTo(profileId);
    }
}
