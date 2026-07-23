package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.config.InvoiceEventPublisher;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import com.positivity.shared.dto.OrderInvoiceLineItem;
import com.positivity.shared.dto.OrderInvoiceResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for the from-order invoice creation path (order parity story C2, #1070): assembly
 * from the order's authoritative figures, orderId idempotency, and workorder dedupe (spec R7.2).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderInvoiceServiceImpl — parity story C2")
class OrderInvoiceServiceImplTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private InvoiceEventPublisher invoiceEventPublisher;

    private OrderInvoiceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderInvoiceServiceImpl(
                invoiceRepository,
                invoiceEventPublisher,
                Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC));
        when(invoiceRepository.findByOrderId(any())).thenReturn(Optional.empty());
        when(invoiceRepository.save(any())).thenAnswer(inv -> {
            Invoice invoice = inv.getArgument(0);
            if (invoice.getId() == null) {
                invoice.setId(UUID.randomUUID());
            }
            return invoice;
        });
    }

    private OrderInvoiceCreationRequest request(UUID workorderId) {
        return OrderInvoiceCreationRequest.builder()
                .orderId(ORDER_ID)
                .workorderId(workorderId)
                .customerId(CUSTOMER_ID)
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.00"))
                .totalAmount(new BigDecimal("108.00"))
                .lines(List.of(OrderInvoiceLineItem.builder()
                        .orderLineId(UUID.randomUUID())
                        .description("Widget")
                        .quantity(new BigDecimal("2"))
                        .unitPrice(new BigDecimal("50.00"))
                        .amount(new BigDecimal("100.00"))
                        .taxAmount(new BigDecimal("8.00"))
                        .type("PART")
                        .build()))
                .build();
    }

    @Test
    @DisplayName("OIS-001: creates a DRAFT invoice carrying the order's authoritative figures")
    void createsInvoiceFromOrder() {
        OrderInvoiceResponse response = service.createInvoiceForOrder(request(null));

        assertThat(response.isExisting()).isFalse();
        assertThat(response.getInvoiceId()).isNotNull();
        assertThat(response.getInvoiceNumber()).startsWith("INV-");
        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.DRAFT.name());
        assertThat(response.getSubtotal()).isEqualByComparingTo("100.00");
        assertThat(response.getTaxAmount()).isEqualByComparingTo("8.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("108.00");
        verify(invoiceEventPublisher).publishInvoiceUpdated(any(Invoice.class));
    }

    @Test
    @DisplayName("OIS-002: anonymous cash sale (no customerId) still produces a receiptable invoice")
    void anonymousSale_createsInvoice() {
        OrderInvoiceCreationRequest anonymous = request(null);
        anonymous.setCustomerId(null);

        OrderInvoiceResponse response = service.createInvoiceForOrder(anonymous);

        assertThat(response.isExisting()).isFalse();
        assertThat(response.getInvoiceId()).isNotNull();
    }

    @Test
    @DisplayName("OIS-003: orderId replay returns the existing invoice, no second create")
    void orderIdReplay_returnsExisting() {
        Invoice existing = new Invoice();
        existing.setId(UUID.randomUUID());
        existing.setOrderId(ORDER_ID);
        existing.setInvoiceNumber("INV-EXISTING");
        existing.setStatus(InvoiceStatus.DRAFT);
        when(invoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(existing));

        OrderInvoiceResponse response = service.createInvoiceForOrder(request(null));

        assertThat(response.isExisting()).isTrue();
        assertThat(response.getInvoiceId()).isEqualTo(existing.getId());
        verify(invoiceRepository, never()).save(any());
        verify(invoiceEventPublisher, never()).publishInvoiceUpdated(any());
    }

    @Test
    @DisplayName("OIS-004: workorder dedupe returns the workorder's invoice and stamps orderId (spec R7.2)")
    void workorderDedupe_returnsWorkorderInvoice() {
        Invoice workorderInvoice = new Invoice();
        workorderInvoice.setId(UUID.randomUUID());
        workorderInvoice.setWorkorderId(WORKORDER_ID);
        workorderInvoice.setInvoiceNumber("INV-WO");
        workorderInvoice.setStatus(InvoiceStatus.DRAFT);
        when(invoiceRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(Optional.of(workorderInvoice));

        OrderInvoiceResponse response = service.createInvoiceForOrder(request(WORKORDER_ID));

        assertThat(response.isExisting()).isTrue();
        assertThat(response.getInvoiceId()).isEqualTo(workorderInvoice.getId());
        assertThat(workorderInvoice.getOrderId()).isEqualTo(ORDER_ID);
    }

    @Test
    @DisplayName("OIS-005: missing orderId / empty lines / negative total are rejected")
    void invalidRequests_rejected() {
        OrderInvoiceCreationRequest noOrder = request(null);
        noOrder.setOrderId(null);
        assertThatThrownBy(() -> service.createInvoiceForOrder(noOrder)).isInstanceOf(IllegalArgumentException.class);

        OrderInvoiceCreationRequest noLines = request(null);
        noLines.setLines(List.of());
        assertThatThrownBy(() -> service.createInvoiceForOrder(noLines)).isInstanceOf(IllegalArgumentException.class);

        OrderInvoiceCreationRequest negative = request(null);
        negative.setTotalAmount(new BigDecimal("-1.00"));
        assertThatThrownBy(() -> service.createInvoiceForOrder(negative)).isInstanceOf(IllegalArgumentException.class);
    }
}
