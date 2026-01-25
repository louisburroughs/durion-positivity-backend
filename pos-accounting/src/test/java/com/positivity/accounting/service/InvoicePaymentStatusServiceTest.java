package com.positivity.accounting.service;

import com.positivity.accounting.domain.InvoiceStatusView;
import com.positivity.accounting.domain.PaymentAppliedEvent;
import com.positivity.accounting.domain.PaymentStatus;
import com.positivity.accounting.entity.InvoiceStatusResponse;
import com.positivity.accounting.entity.PaymentAppliedRequest;
import com.positivity.accounting.repository.InvoiceStatusViewRepository;
import com.positivity.accounting.repository.PaymentAppliedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for InvoicePaymentStatusService.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InvoicePaymentStatusServiceTest {

    @Autowired
    private InvoicePaymentStatusService paymentStatusService;

    @Autowired
    private PaymentAppliedEventRepository paymentEventRepository;

    @Autowired
    private InvoiceStatusViewRepository statusViewRepository;

    @Autowired
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        paymentEventRepository.deleteAll();
        statusViewRepository.deleteAll();
    }

    @Test
    void testFullPayment() {
        // Arrange
        PaymentAppliedRequest request = new PaymentAppliedRequest();
        request.setInvoiceId("INV-001");
        request.setTransactionReference("TXN-001");
        request.setPaymentAmount(new BigDecimal("100.00"));
        request.setInvoiceTotal(new BigDecimal("100.00"));
        request.setIdempotencyKey("key-001");
        request.setPaymentFailed(false);

        // Act
        InvoiceStatusResponse response = paymentStatusService.processPaymentApplied(request);

        // Assert
        assertNotNull(response);
        assertEquals("INV-001", response.getInvoiceId());
        assertEquals(PaymentStatus.PAID, response.getStatus());
        assertEquals(0, response.getTotalPaid().compareTo(new BigDecimal("100.00")));
        assertEquals(0, response.getRemainingBalance().compareTo(BigDecimal.ZERO));
    }

    @Test
    void testPartialPayment() {
        // Arrange
        PaymentAppliedRequest request = new PaymentAppliedRequest();
        request.setInvoiceId("INV-002");
        request.setTransactionReference("TXN-002");
        request.setPaymentAmount(new BigDecimal("50.00"));
        request.setInvoiceTotal(new BigDecimal("100.00"));
        request.setIdempotencyKey("key-002");
        request.setPaymentFailed(false);

        // Act
        InvoiceStatusResponse response = paymentStatusService.processPaymentApplied(request);

        // Assert
        assertNotNull(response);
        assertEquals(PaymentStatus.PARTIALLY_PAID, response.getStatus());
        assertEquals(0, response.getTotalPaid().compareTo(new BigDecimal("50.00")));
        assertEquals(0, response.getRemainingBalance().compareTo(new BigDecimal("50.00")));
    }

    @Test
    void testMultiplePartialPayments() {
        // Arrange - First payment
        PaymentAppliedRequest request1 = new PaymentAppliedRequest();
        request1.setInvoiceId("INV-003");
        request1.setTransactionReference("TXN-003-1");
        request1.setPaymentAmount(new BigDecimal("30.00"));
        request1.setInvoiceTotal(new BigDecimal("100.00"));
        request1.setIdempotencyKey("key-003-1");

        // Act - First payment
        InvoiceStatusResponse response1 = paymentStatusService.processPaymentApplied(request1);
        assertEquals(PaymentStatus.PARTIALLY_PAID, response1.getStatus());

        // Arrange - Second payment
        PaymentAppliedRequest request2 = new PaymentAppliedRequest();
        request2.setInvoiceId("INV-003");
        request2.setTransactionReference("TXN-003-2");
        request2.setPaymentAmount(new BigDecimal("70.00"));
        request2.setInvoiceTotal(new BigDecimal("100.00"));
        request2.setIdempotencyKey("key-003-2");

        // Act - Second payment
        InvoiceStatusResponse response2 = paymentStatusService.processPaymentApplied(request2);

        // Assert
        assertEquals(PaymentStatus.PAID, response2.getStatus());
        assertEquals(0, response2.getTotalPaid().compareTo(new BigDecimal("100.00")));
    }

    @Test
    void testFailedPayment() {
        // Arrange
        PaymentAppliedRequest request = new PaymentAppliedRequest();
        request.setInvoiceId("INV-004");
        request.setTransactionReference("TXN-004");
        request.setPaymentAmount(new BigDecimal("100.00"));
        request.setInvoiceTotal(new BigDecimal("100.00"));
        request.setIdempotencyKey("key-004");
        request.setPaymentFailed(true);

        // Act
        InvoiceStatusResponse response = paymentStatusService.processPaymentApplied(request);

        // Assert - Failed payments don't count toward total
        InvoiceStatusView statusView = statusViewRepository.findByInvoiceId("INV-004").orElseThrow();
        assertEquals(PaymentStatus.UNPAID, statusView.getCurrentStatus());
        assertEquals(0, statusView.getTotalPaid().compareTo(BigDecimal.ZERO));
    }

    @Test
    void testIdempotency() {
        // Arrange
        PaymentAppliedRequest request = new PaymentAppliedRequest();
        request.setInvoiceId("INV-005");
        request.setTransactionReference("TXN-005");
        request.setPaymentAmount(new BigDecimal("100.00"));
        request.setInvoiceTotal(new BigDecimal("100.00"));
        request.setIdempotencyKey("key-005");

        // Act - Process same payment twice
        InvoiceStatusResponse response1 = paymentStatusService.processPaymentApplied(request);
        InvoiceStatusResponse response2 = paymentStatusService.processPaymentApplied(request);

        // Assert - Only one payment event should be created
        long eventCount = paymentEventRepository.findByInvoiceIdOrderByTimestampDesc("INV-005").size();
        assertEquals(1, eventCount);

        // Both responses should be identical
        assertEquals(response1.getStatus(), response2.getStatus());
        assertEquals(response1.getTotalPaid(), response2.getTotalPaid());
    }

    @Test
    void testGetInvoiceStatus() {
        // Arrange - Create an invoice with payment
        PaymentAppliedRequest request = new PaymentAppliedRequest();
        request.setInvoiceId("INV-006");
        request.setTransactionReference("TXN-006");
        request.setPaymentAmount(new BigDecimal("75.00"));
        request.setInvoiceTotal(new BigDecimal("100.00"));
        request.setIdempotencyKey("key-006");

        paymentStatusService.processPaymentApplied(request);

        // Act
        InvoiceStatusResponse response = paymentStatusService.getInvoiceStatus("INV-006");

        // Assert
        assertNotNull(response);
        assertEquals("INV-006", response.getInvoiceId());
        assertEquals(PaymentStatus.PARTIALLY_PAID, response.getStatus());
        assertEquals(0, response.getTotalPaid().compareTo(new BigDecimal("75.00")));
    }
}
