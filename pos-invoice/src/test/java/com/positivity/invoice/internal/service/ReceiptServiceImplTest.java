package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.Receipt;
import com.positivity.invoice.internal.enums.ReceiptDeliveryMethod;
import com.positivity.invoice.internal.enums.ReceiptDeliveryStatus;
import com.positivity.invoice.internal.enums.ReceiptStatus;
import com.positivity.invoice.internal.exception.ReprintLimitExceededException;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.repository.ReceiptRepository;

/**
 * Unit tests for {@link ReceiptServiceImpl} covering Story #7:
 * Receipt Generation.
 *
 * <p>
 * Covers:
 * <ul>
 * <li>AC1 — generateReceipt saves Receipt with status=GENERATED, correct
 * fields</li>
 * <li>AC2 — Reference pattern matches
 * RCP-{invoiceNumber}-{timestamp}-{seq}</li>
 * <li>AC3 — recordPrintDelivery updates delivery record to PRINT/SUCCESS</li>
 * <li>AC4 — sendEmailReceipt records EMAIL delivery with emailAddress</li>
 * <li>AC5 — reprintReceipt increments reprintCount; throws
 * ReprintLimitExceededException at limit</li>
 * <li>Security — generateReceipt requires GENERATE_RECEIPT authority</li>
 * </ul>
 *
 * Issue: #7
 */
@ExtendWith(MockitoExtension.class)
class ReceiptServiceImplTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-01-15T14:30:22Z"), ZoneOffset.UTC);
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID PAYMENT_INTENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID RECEIPT_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final String INVOICE_NUMBER = "INV-12345";
    private static final String CASHIER_ID = "cashier-001";
    private static final String TERMINAL_ID = "POS-001";
    private static final String TEMPLATE_ID = "default";
    private static final String TEMPLATE_VERSION = "1.0";

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private ReceiptRepository receiptRepository;

    @Mock
    private InvoiceRepository invoiceRepository;

    @InjectMocks
    private ReceiptServiceImpl receiptServiceImpl;

    @BeforeEach
    void setUp() {
        withAuthorities("GENERATE_RECEIPT");

        var invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        invoice.setInvoiceNumber(INVOICE_NUMBER);
        lenient().when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        lenient().when(receiptRepository.save(any(Receipt.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Sets up the security context with the provided authority strings.
     */
    private void withAuthorities(String... authorities) {
        var grants = List.of(authorities).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CASHIER_ID, null, grants));
    }

    // -------------------------------------------------------------------------
    // AC1 — generateReceipt saves Receipt with correct fields
    // -------------------------------------------------------------------------

    /**
     * AC1: generateReceipt creates a Receipt with status GENERATED,
     * correct invoiceId and paymentIntentId.
     * Issue: #7
     */
    @Test
    void generateReceipt_success_createsReceiptWithExpectedFields() {
        com.positivity.invoice.service.Receipt saved = receiptServiceImpl.generateReceipt(
                INVOICE_ID, PAYMENT_INTENT_ID, TERMINAL_ID, TEMPLATE_ID, TEMPLATE_VERSION);

        assertThat(saved.getStatus()).isEqualTo(ReceiptStatus.GENERATED);
        assertThat(saved.getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(saved.getPaymentIntentId()).isEqualTo(PAYMENT_INTENT_ID);
        assertThat(saved.getCashierId()).isEqualTo(CASHIER_ID);
    }

    // -------------------------------------------------------------------------
    // AC4 — Reference pattern validation
    // -------------------------------------------------------------------------

    /**
     * AC4: Reference must match pattern RCP-{invoiceNumber}-{yyyyMMddTHHmmssZ}-001.
     * Uses fixed clock 2026-01-15T14:30:22Z → RCP-INV-12345-20260115T143022Z-001.
     * Issue: #7
     */
    @Test
    void generateReceipt_generatesReferenceWithCorrectPattern() {
        ArgumentCaptor<Receipt> captor = ArgumentCaptor.forClass(Receipt.class);

        receiptServiceImpl.generateReceipt(
                INVOICE_ID, PAYMENT_INTENT_ID, TERMINAL_ID, TEMPLATE_ID, TEMPLATE_VERSION);

        verify(receiptRepository).save(captor.capture());
        String reference = captor.getValue().getReference();
        assertThat(reference).startsWith("RCP-INV-12345-");
        assertThat(reference).matches("RCP-INV-12345-\\d{8}T\\d{6}Z-\\d{3}");
    }

    /**
     * AC1: generateReceipt must call repository save exactly once.
     * Issue: #7
     */
    @Test
    void generateReceipt_savesOnce() {
        receiptServiceImpl.generateReceipt(
                INVOICE_ID, PAYMENT_INTENT_ID, TERMINAL_ID, TEMPLATE_ID, TEMPLATE_VERSION);

        verify(receiptRepository, times(1)).save(any(Receipt.class));
    }

    // -------------------------------------------------------------------------
    // Security — GENERATE_RECEIPT authority required
    // -------------------------------------------------------------------------

    /**
     * Security: generateReceipt must throw AccessDeniedException when caller
     * lacks GENERATE_RECEIPT authority.
     * Issue: #7
     */
    @Test
    void generateReceipt_withoutPermission_throwsAccessDeniedException() {
        // Issue #7: override context with no authorities
        withAuthorities();

        assertThatThrownBy(() -> receiptServiceImpl.generateReceipt(
                INVOICE_ID, PAYMENT_INTENT_ID, TERMINAL_ID, TEMPLATE_ID, TEMPLATE_VERSION))
                .isInstanceOf(AccessDeniedException.class);
    }

    // -------------------------------------------------------------------------
    // AC2 — recordPrintDelivery updates delivery to PRINT/SUCCESS
    // -------------------------------------------------------------------------

    /**
     * AC2: recordPrintDelivery must update the receipt delivery record
     * with method=PRINT and the provided status.
     * Issue: #7
     */
    @Test
    void recordPrintDelivery_success_updatesDeliveryRecord() {
        var receipt = buildExistingReceipt(0);
        when(receiptRepository.findById(RECEIPT_ID)).thenReturn(Optional.of(receipt));

        receiptServiceImpl.recordPrintDelivery(RECEIPT_ID, ReceiptDeliveryStatus.SUCCESS);

        ArgumentCaptor<Receipt> captor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).save(captor.capture());
        Receipt updated = captor.getValue();
        assertThat(updated.getDeliveryMethod()).isEqualTo(ReceiptDeliveryMethod.PRINT);
        assertThat(updated.getDeliveryStatus()).isEqualTo(ReceiptDeliveryStatus.SUCCESS);
    }

    // -------------------------------------------------------------------------
    // AC3 — sendEmailReceipt records EMAIL delivery
    // -------------------------------------------------------------------------

    /**
     * AC3: sendEmailReceipt must record a delivery entry with method=EMAIL
     * and the provided email address.
     * Issue: #7
     */
    @Test
    void sendEmailReceipt_success_updatesEmailDeliveryRecord() {
        var receipt = buildExistingReceipt(0);
        when(receiptRepository.findById(RECEIPT_ID)).thenReturn(Optional.of(receipt));

        receiptServiceImpl.sendEmailReceipt(RECEIPT_ID, "customer@example.com", ReceiptDeliveryStatus.SUCCESS);

        ArgumentCaptor<Receipt> captor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).save(captor.capture());
        Receipt updated = captor.getValue();
        assertThat(updated.getDeliveryMethod()).isEqualTo(ReceiptDeliveryMethod.EMAIL);
        assertThat(updated.getDeliveryEmailAddress()).isEqualTo("customer@example.com");
        assertThat(updated.getDeliveryStatus()).isEqualTo(ReceiptDeliveryStatus.SUCCESS);
    }

    // -------------------------------------------------------------------------
    // AC5 — reprintReceipt increments reprintCount / enforces limit
    // -------------------------------------------------------------------------

    /**
     * AC5: reprintReceipt increments reprintCount from 0 to 1 on first reprint.
     * Issue: #7
     */
    @Test
    void reprintReceipt_success_incrementsReprintCount() {
        var receipt = buildExistingReceipt(0);
        when(receiptRepository.findById(RECEIPT_ID)).thenReturn(Optional.of(receipt));

        com.positivity.invoice.service.Receipt saved = receiptServiceImpl.reprintReceipt(RECEIPT_ID,
                "CUSTOMER_REQUEST");
        assertThat(saved.getReprintCount()).isEqualTo(1);
        assertThat(saved.getLastReprintReason()).isEqualTo("CUSTOMER_REQUEST");
        assertThat(saved.getLastReprintedBy()).isEqualTo(CASHIER_ID);
    }

    /**
     * AC5: reprintReceipt must throw ReprintLimitExceededException when
     * reprintCount >= 5 and caller lacks SUPERVISOR_OVERRIDE permission.
     * Issue: #7
     */
    @Test
    void reprintReceipt_atLimit_throwsReprintLimitExceededException() {
        var receipt = buildExistingReceipt(5);
        when(receiptRepository.findById(RECEIPT_ID)).thenReturn(Optional.of(receipt));
        // Issue #7: context has no SUPERVISOR_OVERRIDE authority
        withAuthorities("GENERATE_RECEIPT");

        assertThatThrownBy(() -> receiptServiceImpl.reprintReceipt(RECEIPT_ID, "CUSTOMER_REQUEST"))
                .isInstanceOf(ReprintLimitExceededException.class);
    }

    // -------------------------------------------------------------------------
    // generateReceipt — invoice not found
    // -------------------------------------------------------------------------

    /**
     * generateReceipt must throw ReceiptNotFoundException when the invoice
     * is not found.
     * Issue: #7
     */
    @Test
    void generateReceipt_invoiceNotFound_throwsReceiptNotFoundException() {
        UUID unknownInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(invoiceRepository.findById(unknownInvoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> receiptServiceImpl.generateReceipt(
                unknownInvoiceId, PAYMENT_INTENT_ID, TERMINAL_ID, TEMPLATE_ID, TEMPLATE_VERSION))
                .isInstanceOf(com.positivity.invoice.internal.exception.ReceiptNotFoundException.class)
                .hasMessageContaining(unknownInvoiceId.toString());
    }

    // -------------------------------------------------------------------------
    // recordPrintDelivery — FAILED status + not-found
    // -------------------------------------------------------------------------

    /**
     * recordPrintDelivery must update delivery record with FAILED status.
     * Issue: #7
     */
    @Test
    void recordPrintDelivery_failedStatus_updatesDeliveryRecord() {
        var receipt = buildExistingReceipt(0);
        when(receiptRepository.findById(RECEIPT_ID)).thenReturn(Optional.of(receipt));

        receiptServiceImpl.recordPrintDelivery(RECEIPT_ID, ReceiptDeliveryStatus.FAILED);

        ArgumentCaptor<Receipt> captor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).save(captor.capture());
        assertThat(captor.getValue().getDeliveryStatus()).isEqualTo(ReceiptDeliveryStatus.FAILED);
        assertThat(captor.getValue().getDeliveryMethod()).isEqualTo(ReceiptDeliveryMethod.PRINT);
    }

    /**
     * recordPrintDelivery must throw ReceiptNotFoundException when receipt is not
     * found.
     * Issue: #7
     */
    @Test
    void recordPrintDelivery_receiptNotFound_throwsReceiptNotFoundException() {
        when(receiptRepository.findById(RECEIPT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> receiptServiceImpl.recordPrintDelivery(RECEIPT_ID, ReceiptDeliveryStatus.SUCCESS))
                .isInstanceOf(com.positivity.invoice.internal.exception.ReceiptNotFoundException.class)
                .hasMessageContaining(RECEIPT_ID.toString());
    }

    // -------------------------------------------------------------------------
    // sendEmailReceipt — FAILED status + not-found
    // -------------------------------------------------------------------------

    /**
     * sendEmailReceipt must update delivery record with FAILED status.
     * Issue: #7
     */
    @Test
    void sendEmailReceipt_failedStatus_updatesDeliveryRecord() {
        var receipt = buildExistingReceipt(0);
        when(receiptRepository.findById(RECEIPT_ID)).thenReturn(Optional.of(receipt));

        receiptServiceImpl.sendEmailReceipt(RECEIPT_ID, "customer@example.com", ReceiptDeliveryStatus.FAILED);

        ArgumentCaptor<Receipt> captor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).save(captor.capture());
        assertThat(captor.getValue().getDeliveryStatus()).isEqualTo(ReceiptDeliveryStatus.FAILED);
        assertThat(captor.getValue().getDeliveryMethod()).isEqualTo(ReceiptDeliveryMethod.EMAIL);
        assertThat(captor.getValue().getDeliveryEmailAddress()).isEqualTo("customer@example.com");
    }

    /**
     * sendEmailReceipt must throw ReceiptNotFoundException when receipt is not
     * found.
     * Issue: #7
     */
    @Test
    void sendEmailReceipt_receiptNotFound_throwsReceiptNotFoundException() {
        when(receiptRepository.findById(RECEIPT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> receiptServiceImpl.sendEmailReceipt(
                RECEIPT_ID, "customer@example.com", ReceiptDeliveryStatus.SUCCESS))
                .isInstanceOf(com.positivity.invoice.internal.exception.ReceiptNotFoundException.class)
                .hasMessageContaining(RECEIPT_ID.toString());
    }

    // -------------------------------------------------------------------------
    // reprintReceipt — SUPERVISOR_OVERRIDE bypass + not-found
    // -------------------------------------------------------------------------

    /**
     * reprintReceipt must allow reprint past the limit when caller has
     * SUPERVISOR_OVERRIDE authority.
     * Issue: #7
     */
    @Test
    void reprintReceipt_atLimit_withSupervisorOverride_succeeds() {
        var receipt = buildExistingReceipt(5);
        when(receiptRepository.findById(RECEIPT_ID)).thenReturn(Optional.of(receipt));
        withAuthorities("GENERATE_RECEIPT", "SUPERVISOR_OVERRIDE");

        com.positivity.invoice.service.Receipt saved = receiptServiceImpl.reprintReceipt(
                RECEIPT_ID, "SUPERVISOR_APPROVED");

        assertThat(saved.getReprintCount()).isEqualTo(6);
        assertThat(saved.getLastReprintReason()).isEqualTo("SUPERVISOR_APPROVED");
    }

    /**
     * reprintReceipt must throw ReceiptNotFoundException when receipt is not found.
     * Issue: #7
     */
    @Test
    void reprintReceipt_receiptNotFound_throwsReceiptNotFoundException() {
        when(receiptRepository.findById(RECEIPT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> receiptServiceImpl.reprintReceipt(RECEIPT_ID, "CUSTOMER_REQUEST"))
                .isInstanceOf(com.positivity.invoice.internal.exception.ReceiptNotFoundException.class)
                .hasMessageContaining(RECEIPT_ID.toString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Receipt buildExistingReceipt(int reprintCount) {
        var receipt = new Receipt();
        receipt.setId(RECEIPT_ID);
        receipt.setInvoiceId(INVOICE_ID);
        receipt.setPaymentIntentId(PAYMENT_INTENT_ID);
        receipt.setStatus(ReceiptStatus.GENERATED);
        receipt.setReference("RCP-INV-12345-20260115T143022Z-001");
        receipt.setReprintCount(reprintCount);
        return receipt;
    }
}
