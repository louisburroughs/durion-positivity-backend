package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.CreditMemoGLConfig;
import com.positivity.accounting.internal.dto.CreateCreditMemoRequest;
import com.positivity.accounting.internal.dto.CreditMemoResponse;
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.service.AccountingPeriodServiceImpl;
import com.positivity.accounting.internal.service.CreditMemoServiceImpl;
import com.positivity.accounting.internal.service.GLPostingServiceImpl;
import com.positivity.accounting.internal.service.InvoiceBalanceCalculator;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for CreditMemoService
 *
 * Tests business logic, validation rules, and error handling
 * for Credit Memo operations (CAP-052).
 *
 * ADR-0044 (#842): invoice state comes from the ext_invoice replica via
 * InvoiceBalanceCalculator; the balance due is derived from accounting's own
 * records rather than fetched from pos-invoice.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditMemoService Unit Tests")
class CreditMemoServiceTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private CreditMemoRepository creditMemoRepository;

    @Mock
    private InvoiceBalanceCalculator invoiceBalanceCalculator;

    @Mock
    private GLPostingServiceImpl glPostingService;

    @Mock
    private AccountingPeriodServiceImpl periodService;

    @Mock
    private CreditMemoGLConfig glConfig;

    @InjectMocks
    private CreditMemoServiceImpl service;

    private UUID testInvoiceId;
    private UUID testCustomerId;
    private UUID testCreditMemoId;
    private UUID testRevenueAccountId;
    private UUID testTaxAccountId;
    private UUID testArAccountId;
    private CreateCreditMemoRequest testRequest;
    private CreditMemo testCreditMemo;
    private ExtInvoice testInvoice;

    @BeforeEach
    void setUp() {
        testInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testCreditMemoId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testRevenueAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testTaxAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testArAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        testRequest = new CreateCreditMemoRequest();
        testRequest.setOriginalInvoiceId(testInvoiceId);
        testRequest.setCreditAmount(new BigDecimal("50.00"));
        testRequest.setReasonCode("RETURNED_GOODS");
        testRequest.setJustificationNote("Customer returned defective product");

        testCreditMemo = new CreditMemo();
        testCreditMemo.setCreditMemoId(testCreditMemoId);
        testCreditMemo.setOriginalInvoiceId(testInvoiceId);
        testCreditMemo.setCustomerId(testCustomerId);
        testCreditMemo.setCreditAmount(new BigDecimal("50.00"));
        testCreditMemo.setTaxAmountReversed(new BigDecimal("5.00"));
        // totalAmount is now calculated: 50.00 + 5.00 = 55.00
        testCreditMemo.setReasonCode("RETURNED_GOODS");
        testCreditMemo.setJustificationNote("Customer returned defective product");
        testCreditMemo.setStatus(CreditMemoStatus.POSTED);
        testCreditMemo.setCreatedByUserId("test-user");
        testCreditMemo.setPriorPeriodAdjustment(false);
        testCreditMemo.setCurrency("USD");

        // Replica row for the invoice (owned by pos-invoice, fed via invoice.events.v1).
        // Stored scalar tax of 10.00 on a 110.00 total => net (pre-tax) amount of 100.00.
        testInvoice = replicaInvoice(
                "FINALIZED", "110.00", "10.00", Instant.now(TEST_CLOCK).minusSeconds(86400));

        // Mock GL config (lenient - not all tests reach GL posting)
        lenient().when(glConfig.getRevenueAccountId()).thenReturn(testRevenueAccountId);
        lenient().when(glConfig.getTaxPayableAccountId()).thenReturn(testTaxAccountId);
        lenient().when(glConfig.getArAccountId()).thenReturn(testArAccountId);

        // Mock period service (lenient - not all tests reach period checks)
        lenient().when(periodService.isPriorPeriod(any())).thenReturn(false);
        lenient().when(periodService.getCurrentPeriodId()).thenReturn("2026-02");
    }

    @Test
    @DisplayName("Should create credit memo successfully")
    void testCreateCreditMemo_Success() {
        // Given
        stubReplica(testInvoice, "110.00");
        when(creditMemoRepository.save(any(CreditMemo.class))).thenReturn(testCreditMemo);

        // When
        CreditMemoResponse response = service.createCreditMemo(testRequest, "test-user");

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getCreditMemoId()).isEqualTo(testCreditMemoId);
        assertThat(response.getOriginalInvoiceId()).isEqualTo(testInvoiceId);
        assertThat(response.getCreditAmount()).isEqualByComparingTo("50.00");
        assertThat(response.getTaxAmountReversed()).isEqualByComparingTo("5.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("55.00");
        assertThat(response.getReasonCode()).isEqualTo("RETURNED_GOODS");
        assertThat(response.getStatus()).isEqualTo(CreditMemoStatus.POSTED);
        // balanceAfter = 110.00 - (50.00 + 5.00), derived locally
        assertThat(response.getInvoiceBalanceAfter()).isEqualByComparingTo("55.00");

        // Verify entity was saved
        ArgumentCaptor<CreditMemo> captor = ArgumentCaptor.forClass(CreditMemo.class);
        verify(creditMemoRepository).save(captor.capture());

        CreditMemo saved = captor.getValue();
        assertThat(saved.getCreditAmount()).isEqualByComparingTo("50.00");
        // Stored tax 10.00 pro-rated by credit ratio 50.00/100.00 (net) = 5.00
        assertThat(saved.getTaxAmountReversed()).isEqualByComparingTo("5.00");
        assertThat(saved.getReasonCode()).isEqualTo("RETURNED_GOODS");
        assertThat(saved.getStatus()).isEqualTo(CreditMemoStatus.POSTED);
        assertThat(saved.getCustomerId()).isEqualTo(testCustomerId);
        assertThat(saved.getCurrency()).isEqualTo("USD");

        // Verify GL posting was called
        verify(glPostingService)
                .postCreditMemoReversal(
                        any(UUID.class),
                        eq(testRevenueAccountId),
                        eq(testTaxAccountId),
                        eq(testArAccountId),
                        any(BigDecimal.class),
                        any(BigDecimal.class),
                        anyString(),
                        eq(false),
                        eq(null));
    }

    @Test
    @DisplayName("Should calculate proportional tax reversal correctly")
    void testCreateCreditMemo_TaxCalculation() {
        // Given
        testRequest.setCreditAmount(new BigDecimal("25.00")); // Partial credit

        CreditMemo savedMemo = new CreditMemo();
        savedMemo.setCreditMemoId(testCreditMemoId);
        savedMemo.setCreditAmount(new BigDecimal("25.00"));
        savedMemo.setTaxAmountReversed(new BigDecimal("2.50")); // Proportional tax
        // totalAmount is now calculated: 25.00 + 2.50 = 27.50
        savedMemo.setOriginalInvoiceId(testInvoiceId);
        savedMemo.setCustomerId(testCustomerId);
        savedMemo.setReasonCode("PRICING_ERROR");
        savedMemo.setStatus(CreditMemoStatus.POSTED);
        savedMemo.setCreatedByUserId("test-user");
        savedMemo.setCurrency("USD");
        savedMemo.setPriorPeriodAdjustment(false);

        stubReplica(testInvoice, "110.00");
        when(creditMemoRepository.save(any(CreditMemo.class))).thenReturn(savedMemo);

        // When
        CreditMemoResponse response = service.createCreditMemo(testRequest, "test-user");

        // Then
        assertThat(response.getTaxAmountReversed()).isEqualByComparingTo("2.50");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("27.50");
        // balanceAfter = 110.00 - 27.50, derived locally
        assertThat(response.getInvoiceBalanceAfter()).isEqualByComparingTo("82.50");
    }

    @Test
    @DisplayName("Should throw exception when credit amount exceeds invoice balance")
    void testCreateCreditMemo_AmountExceedsBalance() {
        // Given - invoice balance is $110.00
        testRequest.setCreditAmount(new BigDecimal("200.00"));
        stubReplica(testInvoice, "110.00");

        // When / Then
        assertThatThrownBy(() -> service.createCreditMemo(testRequest, "test-user"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exceeds invoice outstanding balance")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Should throw exception when total credit (including tax) exceeds invoice balance")
    void testCreateCreditMemo_TotalAmountWithTaxExceedsBalance() {
        // Given - invoice balance is $110.00, stored tax 10.00, net 100.00.
        // A creditAmount of $105.00 exceeds the remaining net (100.00), so the
        // final-credit residual path reverses the full stored tax:
        // - creditAmount: $105.00
        // - taxReversed: $10.00 (full stored tax residual)
        // - total: $115.00, which exceeds the $110.00 balance
        testRequest.setCreditAmount(new BigDecimal("105.00"));
        stubReplica(testInvoice, "110.00");

        // When / Then
        assertThatThrownBy(() -> service.createCreditMemo(testRequest, "test-user"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Total credit amount")
                .hasMessageContaining("exceeds invoice outstanding balance")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Should throw exception when invoice has zero remaining balance")
    void testCreateCreditMemo_ZeroRemainingBalance() {
        // Given - invoice is fully paid (derived balance is zero)
        stubReplica(testInvoice, "0.00");

        // When / Then - should fail fast with 409 before attempting division
        assertThatThrownBy(() -> service.createCreditMemo(testRequest, "test-user"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invoice has no remaining balance to credit")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Should throw exception when invoice is not AR-eligible (DRAFT)")
    void testCreateCreditMemo_DraftInvoice() {
        // Given - a DRAFT invoice never entered AR
        testInvoice = replicaInvoice("DRAFT", "110.00", "10.00", null);
        when(invoiceBalanceCalculator.findInvoice(testInvoiceId)).thenReturn(Optional.of(testInvoice));
        when(invoiceBalanceCalculator.isArEligible(testInvoice)).thenReturn(false);

        // When / Then
        assertThatThrownBy(() -> service.createCreditMemo(testRequest, "test-user"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("DRAFT invoices")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Should throw 404 when invoice is missing from the replica")
    void testCreateCreditMemo_InvoiceMissingFromReplica() {
        // Given
        when(invoiceBalanceCalculator.findInvoice(testInvoiceId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.createCreditMemo(testRequest, "test-user"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found in the invoice replica")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should detect and flag prior period adjustment")
    void testCreateCreditMemo_PriorPeriodAdjustment() {
        // Given - invoice finalized in a prior period
        Instant priorPeriodDate = Instant.now(TEST_CLOCK).minusSeconds(2592000); // 30 days ago
        testInvoice = replicaInvoice("FINALIZED", "110.00", "10.00", priorPeriodDate);

        testCreditMemo.setPriorPeriodAdjustment(true);
        testCreditMemo.setOriginalPeriodId("2026-01");

        stubReplica(testInvoice, "110.00");
        when(periodService.isPriorPeriod(priorPeriodDate)).thenReturn(true);
        when(periodService.getPeriodIdForDate(priorPeriodDate)).thenReturn("2026-01");
        when(creditMemoRepository.save(any(CreditMemo.class))).thenReturn(testCreditMemo);

        // When
        CreditMemoResponse response = service.createCreditMemo(testRequest, "test-user");

        // Then
        assertThat(response.getPriorPeriodAdjustment()).isTrue();
        assertThat(response.getOriginalPeriodId()).isEqualTo("2026-01");

        // Verify GL posting was called with prior period flag
        verify(glPostingService)
                .postCreditMemoReversal(
                        any(UUID.class),
                        any(UUID.class),
                        any(UUID.class),
                        any(UUID.class),
                        any(BigDecimal.class),
                        any(BigDecimal.class),
                        anyString(),
                        eq(true),
                        eq("2026-01"));
    }

    @Test
    @DisplayName("Should list credit memos by customer ID")
    void testListCreditMemos_ByCustomer() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        List<CreditMemo> creditMemos = List.of(testCreditMemo);
        Page<CreditMemo> page = new PageImpl<>(creditMemos, pageable, 1);

        when(creditMemoRepository.findByCustomerId(testCustomerId, pageable)).thenReturn(page);

        // When
        Page<CreditMemoResponse> result = service.listCreditMemos(testCustomerId, null, null, pageable);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getCreditMemoId()).isEqualTo(testCreditMemoId);

        verify(creditMemoRepository).findByCustomerId(testCustomerId, pageable);
    }

    @Test
    @DisplayName("Should list credit memos by invoice ID")
    void testListCreditMemos_ByInvoice() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        List<CreditMemo> creditMemos = List.of(testCreditMemo);
        Page<CreditMemo> page = new PageImpl<>(creditMemos, pageable, 1);

        when(creditMemoRepository.findByOriginalInvoiceId(testInvoiceId, pageable))
                .thenReturn(page);

        // When
        Page<CreditMemoResponse> result = service.listCreditMemos(null, testInvoiceId, null, pageable);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getOriginalInvoiceId()).isEqualTo(testInvoiceId);

        verify(creditMemoRepository).findByOriginalInvoiceId(testInvoiceId, pageable);
    }

    @Test
    @DisplayName("Should list credit memos by status")
    void testListCreditMemos_ByStatus() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        List<CreditMemo> creditMemos = List.of(testCreditMemo);
        Page<CreditMemo> page = new PageImpl<>(creditMemos, pageable, 1);

        when(creditMemoRepository.findByStatus(CreditMemoStatus.POSTED, pageable))
                .thenReturn(page);

        // When
        Page<CreditMemoResponse> result = service.listCreditMemos(null, null, CreditMemoStatus.POSTED, pageable);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo(CreditMemoStatus.POSTED);

        verify(creditMemoRepository).findByStatus(CreditMemoStatus.POSTED, pageable);
    }

    @Test
    @DisplayName("Should get credit memo by ID")
    void testGetCreditMemo_Success() {
        // Given
        when(creditMemoRepository.findById(testCreditMemoId)).thenReturn(Optional.of(testCreditMemo));
        stubReplica(testInvoice, "110.00");

        // When
        CreditMemoResponse response = service.getCreditMemo(testCreditMemoId);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getCreditMemoId()).isEqualTo(testCreditMemoId);
        assertThat(response.getCreditAmount()).isEqualByComparingTo("50.00");
        assertThat(response.getInvoiceBalanceAfter()).isEqualByComparingTo("110.00");

        verify(creditMemoRepository).findById(testCreditMemoId);
        verify(invoiceBalanceCalculator).findInvoice(testInvoiceId);
    }

    @Test
    @DisplayName("Should return zero balance when the invoice is missing from the replica")
    void testGetCreditMemo_InvoiceMissingFromReplica() {
        // Given
        when(creditMemoRepository.findById(testCreditMemoId)).thenReturn(Optional.of(testCreditMemo));
        when(invoiceBalanceCalculator.findInvoice(testInvoiceId)).thenReturn(Optional.empty());

        // When
        CreditMemoResponse response = service.getCreditMemo(testCreditMemoId);

        // Then - balance unavailable but the request does not fail
        assertThat(response).isNotNull();
        assertThat(response.getInvoiceBalanceAfter()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Should throw 404 when credit memo not found")
    void testGetCreditMemo_NotFound() {
        // Given
        UUID nonExistentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(creditMemoRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.getCreditMemo(nonExistentId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Credit Memo not found")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should handle different reason codes")
    void testCreateCreditMemo_DifferentReasonCodes() {
        // Given
        String[] reasonCodes = {"RETURNED_GOODS", "PRICING_ERROR", "SERVICE_CREDIT", "BILLING_ERROR", "DAMAGED_GOODS"};

        stubReplica(testInvoice, "110.00");

        for (String reasonCode : reasonCodes) {
            testRequest.setReasonCode(reasonCode);
            testCreditMemo.setReasonCode(reasonCode);
            when(creditMemoRepository.save(any(CreditMemo.class))).thenReturn(testCreditMemo);

            // When
            CreditMemoResponse response = service.createCreditMemo(testRequest, "test-user");

            // Then
            assertThat(response.getReasonCode()).isEqualTo(reasonCode);
        }
    }

    @Test
    @DisplayName("Should handle optional justification note")
    void testCreateCreditMemo_NoJustificationNote() {
        // Given
        testRequest.setJustificationNote(null);
        testCreditMemo.setJustificationNote(null);
        stubReplica(testInvoice, "110.00");
        when(creditMemoRepository.save(any(CreditMemo.class))).thenReturn(testCreditMemo);

        // When
        CreditMemoResponse response = service.createCreditMemo(testRequest, "test-user");

        // Then
        assertThat(response.getJustificationNote()).isNull();
    }

    // ========================================
    // Stored-tax reversal semantics (issue #953, Odoo parity D-4)
    // ========================================

    @Test
    @DisplayName("Regression: tax reversal derives from stored invoice tax, not a fictional 10% of balance")
    void testStoredTax_NoFictionalTenPercent() {
        // Given - invoice with NO stored tax; the legacy code would have invented
        // 10% of the balance (110.00 * 0.10 * 50/110 = 5.00)
        testInvoice = replicaInvoice("FINALIZED", "110.00", null, Instant.now(TEST_CLOCK));
        stubReplica(testInvoice, "110.00");
        stubSaveEcho();

        // When
        CreditMemoResponse response = service.createCreditMemo(testRequest, "test-user");

        // Then - no stored tax means nothing to reverse
        assertThat(response.getTaxAmountReversed()).isEqualByComparingTo("0.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("50.00");
        verify(glPostingService)
                .postCreditMemoReversal(
                        any(UUID.class),
                        eq(testRevenueAccountId),
                        eq(testTaxAccountId),
                        eq(testArAccountId),
                        eq(new BigDecimal("50.00")),
                        eq(new BigDecimal("0.00")),
                        anyString(),
                        eq(false),
                        eq(null));
    }

    @Test
    @DisplayName("Partial credit rounds pro-rated stored tax HALF_UP at currency scale (35.59 * 0.5 -> 17.80)")
    void testStoredTax_RatioRoundingHalfUp() {
        // Given - tax 35.59 on net 200.00 (total 235.59); credit half the net
        testInvoice = replicaInvoice("FINALIZED", "235.59", "35.59", Instant.now(TEST_CLOCK));
        stubReplica(testInvoice, "235.59");
        stubSaveEcho();
        testRequest.setCreditAmount(new BigDecimal("100.00"));

        // When
        CreditMemoResponse response = service.createCreditMemo(testRequest, "test-user");

        // Then - 35.59 * 0.5 = 17.795 -> HALF_UP -> 17.80
        assertThat(response.getTaxAmountReversed()).isEqualByComparingTo("17.80");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("117.80");
    }

    @Test
    @DisplayName("Final credit posts the tax residual so cumulative reversals sum exactly to stored tax (17.79)")
    void testStoredTax_FinalCreditResidual() {
        // Given - same 35.59-tax invoice; a prior POSTED memo credited 100.00 net
        // and reversed 17.80 tax; this credit consumes the remaining net
        testInvoice = replicaInvoice("FINALIZED", "235.59", "35.59", Instant.now(TEST_CLOCK));
        stubReplica(testInvoice, "117.79");
        stubPriorCredits("100.00", "17.80");
        stubSaveEcho();
        testRequest.setCreditAmount(new BigDecimal("100.00"));

        // When
        CreditMemoResponse response = service.createCreditMemo(testRequest, "test-user");

        // Then - residual = 35.59 - 17.80 = 17.79 (not round(35.59 * 0.5) = 17.80)
        assertThat(response.getTaxAmountReversed()).isEqualByComparingTo("17.79");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("117.79");
        assertThat(response.getInvoiceBalanceAfter()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Single full credit reverses the stored tax exactly")
    void testStoredTax_SingleFullCreditExactness() {
        // Given - full-net credit in one memo
        testInvoice = replicaInvoice("FINALIZED", "235.59", "35.59", Instant.now(TEST_CLOCK));
        stubReplica(testInvoice, "235.59");
        stubSaveEcho();
        testRequest.setCreditAmount(new BigDecimal("200.00"));

        // When
        CreditMemoResponse response = service.createCreditMemo(testRequest, "test-user");

        // Then - cumulative reversal equals the stored tax with a single credit
        assertThat(response.getTaxAmountReversed()).isEqualByComparingTo("35.59");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("235.59");
        assertThat(response.getInvoiceBalanceAfter()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Adversarial: penny tax (0.01) is not lost across partial credits")
    void testStoredTax_PennyTaxResidual() {
        // Given - tax 0.01 on net 100.00 (total 100.01); a third-part credit
        testInvoice = replicaInvoice("FINALIZED", "100.01", "0.01", Instant.now(TEST_CLOCK));
        stubReplica(testInvoice, "100.01");
        stubSaveEcho();
        testRequest.setCreditAmount(new BigDecimal("33.33"));

        // When - partial credit: 0.01 * 0.3333 rounds to 0.00
        CreditMemoResponse partial = service.createCreditMemo(testRequest, "test-user");
        assertThat(partial.getTaxAmountReversed()).isEqualByComparingTo("0.00");

        // Given - two prior partials credited 66.66 net with 0.00 tax reversed
        stubReplica(testInvoice, "33.35");
        stubPriorCredits("66.66", "0.00");
        testRequest.setCreditAmount(new BigDecimal("33.34"));

        // When - final credit consumes the remaining net
        CreditMemoResponse finalCredit = service.createCreditMemo(testRequest, "test-user");

        // Then - the whole 0.01 tax surfaces on the final line
        assertThat(finalCredit.getTaxAmountReversed()).isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("Adversarial: 99.99 tax over three equal credits sums exactly (33.33 + 33.33 + 33.33)")
    void testStoredTax_HighTaxThreeWayResidual() {
        // Given - tax 99.99 on net 300.00 (total 399.99); first third
        testInvoice = replicaInvoice("FINALIZED", "399.99", "99.99", Instant.now(TEST_CLOCK));
        stubReplica(testInvoice, "399.99");
        stubSaveEcho();
        testRequest.setCreditAmount(new BigDecimal("100.00"));

        // When - 99.99 * (100/300) = 33.33
        CreditMemoResponse first = service.createCreditMemo(testRequest, "test-user");
        assertThat(first.getTaxAmountReversed()).isEqualByComparingTo("33.33");

        // Given - two thirds already credited; final third
        stubReplica(testInvoice, "133.33");
        stubPriorCredits("200.00", "66.66");

        // When - residual = 99.99 - 66.66 = 33.33
        CreditMemoResponse last = service.createCreditMemo(testRequest, "test-user");

        // Then - cumulative 66.66 + 33.33 = 99.99 = stored tax
        assertThat(last.getTaxAmountReversed()).isEqualByComparingTo("33.33");
    }

    @Test
    @DisplayName("Sequence: single partial then final credit reverses 17.80 then 17.79")
    void testStoredTax_PartialThenFinalSequence() {
        // Given - Odoo residual vector: tax 35.59 split 50/50 across two credits
        testInvoice = replicaInvoice("FINALIZED", "235.59", "35.59", Instant.now(TEST_CLOCK));
        stubReplica(testInvoice, "235.59");
        stubSaveEcho();
        testRequest.setCreditAmount(new BigDecimal("100.00"));

        // When - first half
        CreditMemoResponse first = service.createCreditMemo(testRequest, "test-user");
        assertThat(first.getTaxAmountReversed()).isEqualByComparingTo("17.80");

        // Given - the first memo is now POSTED history
        stubReplica(testInvoice, "117.79");
        stubPriorCredits("100.00", "17.80");

        // When - second half (final)
        CreditMemoResponse second = service.createCreditMemo(testRequest, "test-user");

        // Then - 17.80 + 17.79 = 35.59 exactly
        assertThat(second.getTaxAmountReversed()).isEqualByComparingTo("17.79");
        assertThat(first.getTaxAmountReversed().add(second.getTaxAmountReversed()))
                .isEqualByComparingTo("35.59");
    }

    // ========================================
    // Helper Methods
    // ========================================

    private ExtInvoice replicaInvoice(String status, String total, String tax, Instant finalizedAt) {
        return ExtInvoice.builder()
                .invoiceId(testInvoiceId)
                .workorderId(UUID.fromString("00000000-0000-0000-0000-0000000000ff"))
                .partyId(testCustomerId.toString())
                .status(status)
                .total(new BigDecimal(total))
                .tax(tax != null ? new BigDecimal(tax) : null)
                .finalizedAt(finalizedAt)
                .aggregateVersion(1L)
                .updatedAt(Instant.now(TEST_CLOCK))
                .build();
    }

    private void stubReplica(ExtInvoice invoice, String balanceDue) {
        lenient()
                .when(invoiceBalanceCalculator.findInvoice(invoice.getInvoiceId()))
                .thenReturn(Optional.of(invoice));
        lenient().when(invoiceBalanceCalculator.isArEligible(invoice)).thenReturn(true);
        lenient().when(invoiceBalanceCalculator.balanceDue(invoice)).thenReturn(new BigDecimal(balanceDue));
        stubPriorCredits("0.00", "0.00");
    }

    /** Stub the sums of previously POSTED credit memos (revenue portion / reversed tax). */
    private void stubPriorCredits(String creditedRevenue, String reversedTax) {
        lenient()
                .when(creditMemoRepository.sumCreditAmountByInvoiceIdAndStatus(testInvoiceId, CreditMemoStatus.POSTED))
                .thenReturn(new BigDecimal(creditedRevenue));
        lenient()
                .when(creditMemoRepository.sumTaxReversedAmountByInvoiceIdAndStatus(
                        testInvoiceId, CreditMemoStatus.POSTED))
                .thenReturn(new BigDecimal(reversedTax));
    }

    /** Echo the saved entity back (with an id) so responses reflect the computed amounts. */
    private void stubSaveEcho() {
        when(creditMemoRepository.save(any(CreditMemo.class))).thenAnswer(invocation -> {
            CreditMemo memo = invocation.getArgument(0);
            memo.setCreditMemoId(testCreditMemoId);
            return memo;
        });
    }
}
