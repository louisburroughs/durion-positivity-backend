package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.invoice.internal.dto.ReceiptViewResponse;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.PaymentIntent;
import com.positivity.invoice.internal.entity.Receipt;
import com.positivity.invoice.internal.enums.ReceiptDeliveryMethod;
import com.positivity.invoice.internal.enums.ReceiptDeliveryStatus;
import com.positivity.invoice.internal.enums.ReceiptStatus;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.exception.ReceiptNotFoundException;
import com.positivity.invoice.internal.exception.ReprintLimitExceededException;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.repository.PaymentIntentRepository;
import com.positivity.invoice.internal.repository.ReceiptRepository;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
    private static final UUID OTHER_INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
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

    @Mock
    private PaymentIntentRepository paymentIntentRepository;

    @InjectMocks
    private ReceiptServiceImpl receiptServiceImpl;

    @BeforeEach
    void setUp() {
        withAuthorities("GENERATE_RECEIPT");

        var invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        invoice.setInvoiceNumber(INVOICE_NUMBER);
        var paymentIntent = new PaymentIntent();
        paymentIntent.setId(PAYMENT_INTENT_ID);
        paymentIntent.setInvoice(invoice);
        lenient().when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        lenient().when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(paymentIntent));
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
        var grants =
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList();
        var auth = new UsernamePasswordAuthenticationToken(CASHIER_ID, null, grants);
        auth.setDetails(java.util.Map.of(GatewaySecurityConstants.DETAIL_USERNAME, CASHIER_ID));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Sets up the security context with the given location scope on top of GENERATE_RECEIPT and
     * invoice:invoice:view, for the getReceipt location-scope tests (#2214, ADR-0061 §3).
     */
    private void withLocationScope(LocationScope scope) {
        var grants = List.of(
                new SimpleGrantedAuthority("GENERATE_RECEIPT"), new SimpleGrantedAuthority(InvoicePermissions.VIEW));
        var auth = new UsernamePasswordAuthenticationToken(CASHIER_ID, null, grants);
        auth.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME, CASHIER_ID,
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE, scope));
        SecurityContextHolder.getContext().setAuthentication(auth);
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
        com.positivity.invoice.internal.service.Receipt saved = receiptServiceImpl.generateReceipt(
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
     * AC4: Reference must match pattern RCP-{invoiceNumber}-{yyyyMMddTHHmmssZ}-{seq}.
     * Uses fixed clock 2026-01-15T14:30:22Z; first sequence suffix is 001.
     * Issue: #7
     */
    @Test
    void generateReceipt_generatesReferenceWithCorrectPattern() {
        ArgumentCaptor<Receipt> captor = ArgumentCaptor.forClass(Receipt.class);

        receiptServiceImpl.generateReceipt(INVOICE_ID, PAYMENT_INTENT_ID, TERMINAL_ID, TEMPLATE_ID, TEMPLATE_VERSION);

        verify(receiptRepository).save(captor.capture());
        String reference = captor.getValue().getReference();
        assertThat(reference).startsWith("RCP-INV-12345-").matches("RCP-INV-12345-\\d{8}T\\d{6}Z-\\d{3}");
    }

    @Test
    void generateReceipt_secondReceiptForInvoice_usesIncrementedReferenceSuffix() {
        when(receiptRepository.countByInvoice_Id(INVOICE_ID)).thenReturn(1L);

        com.positivity.invoice.internal.service.Receipt saved = receiptServiceImpl.generateReceipt(
                INVOICE_ID, PAYMENT_INTENT_ID, TERMINAL_ID, TEMPLATE_ID, TEMPLATE_VERSION);

        assertThat(saved.getReference()).endsWith("-002");
    }

    /**
     * AC1: generateReceipt must call repository save exactly once.
     * Issue: #7
     */
    @Test
    void generateReceipt_savesOnce() {
        receiptServiceImpl.generateReceipt(INVOICE_ID, PAYMENT_INTENT_ID, TERMINAL_ID, TEMPLATE_ID, TEMPLATE_VERSION);

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

        com.positivity.invoice.internal.service.Receipt saved =
                receiptServiceImpl.reprintReceipt(RECEIPT_ID, "CUSTOMER_REQUEST");
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
     * generateReceipt must throw InvoiceNotFoundException when the invoice
     * is not found.
     * Issue: #7
     */
    @Test
    void generateReceipt_invoiceNotFound_throwsInvoiceNotFoundException() {
        UUID unknownInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(invoiceRepository.findById(unknownInvoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> receiptServiceImpl.generateReceipt(
                        unknownInvoiceId, PAYMENT_INTENT_ID, TERMINAL_ID, TEMPLATE_ID, TEMPLATE_VERSION))
                .isInstanceOf(InvoiceNotFoundException.class)
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

        com.positivity.invoice.internal.service.Receipt saved =
                receiptServiceImpl.reprintReceipt(RECEIPT_ID, "SUPERVISOR_APPROVED");

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
    // getReceipt — issue #2214
    // -------------------------------------------------------------------------

    /**
     * #2214: getReceipt returns a view with every field mapped from the receipt,
     * its invoice and its payment intent.
     */
    @Test
    void getReceipt_found_returnsFullyMappedView() {
        var receipt = buildFullReceipt();
        when(receiptRepository.findByIdAndInvoice_Id(RECEIPT_ID, INVOICE_ID)).thenReturn(Optional.of(receipt));

        ReceiptViewResponse view = receiptServiceImpl.getReceipt(INVOICE_ID, RECEIPT_ID);

        assertThat(view.getReceiptId()).isEqualTo(RECEIPT_ID);
        assertThat(view.getReference()).isEqualTo("RCP-INV-12345-20260115T143022Z-001");
        assertThat(view.getStatus()).isEqualTo(ReceiptStatus.GENERATED);
        assertThat(view.getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(view.getInvoiceNumber()).isEqualTo(INVOICE_NUMBER);
        assertThat(view.getPaymentIntentId()).isEqualTo(PAYMENT_INTENT_ID);
        assertThat(view.getPaidAmount()).isEqualByComparingTo(new BigDecimal("149.99"));
        assertThat(view.getPaymentMethod()).isEqualTo("stripe");
        assertThat(view.getGatewayReference()).isEqualTo("ch_3P0a1b2c3d4e5f");
        assertThat(view.getCashierId()).isEqualTo(CASHIER_ID);
        assertThat(view.getTerminalId()).isEqualTo(TERMINAL_ID);
        assertThat(view.getTemplateId()).isEqualTo(TEMPLATE_ID);
        assertThat(view.getTemplateVersion()).isEqualTo(TEMPLATE_VERSION);
        assertThat(view.getDeliveryMethod()).isEqualTo(ReceiptDeliveryMethod.EMAIL);
        assertThat(view.getDeliveryStatus()).isEqualTo(ReceiptDeliveryStatus.SUCCESS);
        assertThat(view.getDeliveryEmailAddress()).isEqualTo("customer@example.com");
        assertThat(view.getReprintCount()).isEqualTo(2);
        assertThat(view.getLastReprintReason()).isEqualTo("CUSTOMER_REQUEST");
        assertThat(view.getLastReprintedBy()).isEqualTo(CASHIER_ID);
        assertThat(view.getCreatedAt()).isEqualTo(TEST_CLOCK.instant());
    }

    /**
     * #2214: getReceipt must throw ReceiptNotFoundException when no receipt with that id
     * exists.
     */
    @Test
    void getReceipt_receiptNotFound_throwsReceiptNotFoundException() {
        when(receiptRepository.findByIdAndInvoice_Id(RECEIPT_ID, INVOICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> receiptServiceImpl.getReceipt(INVOICE_ID, RECEIPT_ID))
                .isInstanceOf(ReceiptNotFoundException.class)
                .hasMessageContaining(RECEIPT_ID.toString());
    }

    /**
     * #2214: getReceipt must throw ReceiptNotFoundException — not leak the receipt — when the
     * receipt exists but belongs to a different invoice.
     */
    @Test
    void getReceipt_belongsToDifferentInvoice_throwsReceiptNotFoundException() {
        when(receiptRepository.findByIdAndInvoice_Id(RECEIPT_ID, OTHER_INVOICE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> receiptServiceImpl.getReceipt(OTHER_INVOICE_ID, RECEIPT_ID))
                .isInstanceOf(ReceiptNotFoundException.class)
                .hasMessageContaining(RECEIPT_ID.toString());
    }

    @Nested
    @DisplayName("getReceipt location scope (ADR-0061 §3, #1872 pattern applied to #2214)")
    class GetReceiptLocationScope {

        @Test
        @DisplayName("receipt's invoice location out of reach: LocationScopeDeniedException, no view returned")
        void outOfReach_denies() {
            withLocationScope(viewScopedTo(OTHER_SHOP));
            var receipt = buildFullReceipt();
            when(receiptRepository.findByIdAndInvoice_Id(RECEIPT_ID, INVOICE_ID))
                    .thenReturn(Optional.of(receipt));

            assertThatThrownBy(() -> receiptServiceImpl.getReceipt(INVOICE_ID, RECEIPT_ID))
                    .isInstanceOf(LocationScopeDeniedException.class)
                    .asInstanceOf(
                            org.assertj.core.api.InstanceOfAssertFactories.type(LocationScopeDeniedException.class))
                    .satisfies(denied -> {
                        assertThat(denied.permission()).isEqualTo(InvoicePermissions.VIEW);
                        assertThat(denied.locationId()).isEqualTo(RECEIPT_LOCATION_ID.toString());
                    });
        }

        @Test
        @DisplayName("receipt's invoice location in reach: full view is returned")
        void inReach_returnsView() {
            withLocationScope(viewScopedTo(REGION_NODE));
            var receipt = buildFullReceipt();
            when(receiptRepository.findByIdAndInvoice_Id(RECEIPT_ID, INVOICE_ID))
                    .thenReturn(Optional.of(receipt));

            ReceiptViewResponse view = receiptServiceImpl.getReceipt(INVOICE_ID, RECEIPT_ID);

            assertThat(view.getReceiptId()).isEqualTo(RECEIPT_ID);
            assertThat(view.getInvoiceId()).isEqualTo(INVOICE_ID);
        }

        @Test
        @DisplayName("pre-rollout token (no loc_* claims): behavior unchanged, full view is returned")
        void preRolloutToken_unchanged() {
            withLocationScope(LocationScope.unscoped());
            var receipt = buildFullReceipt();
            when(receiptRepository.findByIdAndInvoice_Id(RECEIPT_ID, INVOICE_ID))
                    .thenReturn(Optional.of(receipt));

            ReceiptViewResponse view = receiptServiceImpl.getReceipt(INVOICE_ID, RECEIPT_ID);

            assertThat(view.getReceiptId()).isEqualTo(RECEIPT_ID);
            assertThat(view.getInvoiceId()).isEqualTo(INVOICE_ID);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static final UUID RECEIPT_LOCATION_ID = UUID.fromString("01960003-0000-7000-8000-000000000001");

    /** The node a scoped caller is assigned: a region above the receipt's invoice location. */
    private static final UUID REGION_NODE = UUID.fromString("019200aa-0000-7000-8000-00000000a000");

    private static final UUID OTHER_SHOP = UUID.fromString("019200aa-0000-7000-8000-00000000000b");

    /** Replica stand-in for the getReceipt location-scope tests (#2214, mirrors InvoiceServiceImplTest). */
    private static final LocationAncestorResolver RESOLVER = id -> {
        if (id.equals(RECEIPT_LOCATION_ID)) {
            return new AncestorSets(Set.of(id), Set.of(id, REGION_NODE));
        }
        if (id.equals(OTHER_SHOP)) {
            return new AncestorSets(Set.of(id), Set.of(id));
        }
        return AncestorSets.EMPTY;
    };

    /** A caller whose invoice:invoice:view is scoped (OTHER dimension) to the given assigned nodes. */
    private static LocationScope viewScopedTo(UUID... nodes) {
        return LocationScope.of(Set.of(), Set.of(InvoicePermissions.VIEW), Optional.of(Set.of(nodes)), true, RESOLVER);
    }

    private Receipt buildFullReceipt() {
        var invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        invoice.setInvoiceNumber(INVOICE_NUMBER);
        invoice.setLocationId(RECEIPT_LOCATION_ID);

        var paymentIntent = new PaymentIntent();
        paymentIntent.setId(PAYMENT_INTENT_ID);
        paymentIntent.setInvoice(invoice);
        paymentIntent.setCapturedAmount(new BigDecimal("149.99"));
        paymentIntent.setGatewayProvider("stripe");
        paymentIntent.setGatewayReference("ch_3P0a1b2c3d4e5f");

        var receipt = new Receipt();
        receipt.setId(RECEIPT_ID);
        receipt.setInvoice(invoice);
        receipt.setPaymentIntent(paymentIntent);
        receipt.setReference("RCP-INV-12345-20260115T143022Z-001");
        receipt.setStatus(ReceiptStatus.GENERATED);
        receipt.setCashierId(CASHIER_ID);
        receipt.setTerminalId(TERMINAL_ID);
        receipt.setTemplateId(TEMPLATE_ID);
        receipt.setTemplateVersion(TEMPLATE_VERSION);
        receipt.setDeliveryMethod(ReceiptDeliveryMethod.EMAIL);
        receipt.setDeliveryStatus(ReceiptDeliveryStatus.SUCCESS);
        receipt.setDeliveryEmailAddress("customer@example.com");
        receipt.setReprintCount(2);
        receipt.setLastReprintReason("CUSTOMER_REQUEST");
        receipt.setLastReprintedBy(CASHIER_ID);
        receipt.setCreatedAt(TEST_CLOCK.instant());
        return receipt;
    }

    private Receipt buildExistingReceipt(int reprintCount) {
        var receipt = new Receipt();
        receipt.setId(RECEIPT_ID);
        var invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        receipt.setInvoice(invoice);
        var paymentIntent = new PaymentIntent();
        paymentIntent.setId(PAYMENT_INTENT_ID);
        paymentIntent.setInvoice(invoice);
        receipt.setPaymentIntent(paymentIntent);
        receipt.setStatus(ReceiptStatus.GENERATED);
        receipt.setReference("RCP-INV-12345-20260115T143022Z-001");
        receipt.setReprintCount(reprintCount);
        return receipt;
    }
}
