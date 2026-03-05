package com.positivity.accounting.service;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.positivity.accounting.internal.client.InvoiceServiceClient;
import com.positivity.accounting.internal.client.InvoiceServiceException;
import com.positivity.accounting.internal.config.CreditMemoGLConfig;
import com.positivity.accounting.internal.dto.ApplyCreditMemoRequest;
import com.positivity.accounting.internal.dto.ApplyCreditMemoResponse;
import com.positivity.accounting.internal.enums.InvoiceStatus;
import com.positivity.accounting.internal.dto.CreateCreditMemoRequest;
import com.positivity.accounting.internal.dto.CreditMemoResponse;
import com.positivity.accounting.internal.dto.InvoiceDetails;
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.service.AccountingPeriodServiceImpl;
import com.positivity.accounting.internal.service.CreditMemoServiceImpl;
import com.positivity.accounting.internal.service.GLPostingServiceImpl;

/**
 * Unit tests for CreditMemoService
 * 
 * Tests business logic, validation rules, and error handling
 * for Credit Memo operations (CAP-052).
 * 
 * Phase 2.1: Tests updated with mocks for InvoiceServiceClient,
 * GLPostingService, AccountingPeriodService, and CreditMemoGLConfig.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditMemoService Unit Tests")
class CreditMemoServiceTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Mock
        private CreditMemoRepository creditMemoRepository;

        @Mock
        private InvoiceServiceClient invoiceServiceClient;

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
        private InvoiceDetails testInvoice;
        private ApplyCreditMemoResponse testInvoiceResponse;

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

                // Mock invoice details
                testInvoice = InvoiceDetails.builder()
                                .invoiceId(testInvoiceId)
                                .customerId(testCustomerId)
                                .status(InvoiceStatus.OPEN)
                                .totalAmount(new BigDecimal("110.00"))
                                .balanceDue(new BigDecimal("110.00"))
                                .currency("USD")
                                .invoiceDate(Instant.now(TEST_CLOCK).minusSeconds(86400)) // Yesterday
                                .build();

                // Mock invoice response after CM applied
                testInvoiceResponse = ApplyCreditMemoResponse.builder()
                                .invoiceId(testInvoiceId)
                                .balanceBefore(new BigDecimal("110.00"))
                                .balanceAfter(new BigDecimal("55.00"))
                                .status(InvoiceStatus.OPEN)
                                .creditMemoApplied(new BigDecimal("55.00"))
                                .build();

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
                when(invoiceServiceClient.getInvoiceDetails(testInvoiceId)).thenReturn(testInvoice);
                when(creditMemoRepository.save(any(CreditMemo.class))).thenReturn(testCreditMemo);
                when(invoiceServiceClient.applyCreditMemo(eq(testInvoiceId), any(ApplyCreditMemoRequest.class)))
                                .thenReturn(testInvoiceResponse);

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
                assertThat(response.getInvoiceBalanceAfter()).isEqualByComparingTo("55.00");

                // Verify entity was saved
                ArgumentCaptor<CreditMemo> captor = ArgumentCaptor.forClass(CreditMemo.class);
                verify(creditMemoRepository).save(captor.capture());

                CreditMemo saved = captor.getValue();
                assertThat(saved.getCreditAmount()).isEqualByComparingTo("50.00");
                assertThat(saved.getReasonCode()).isEqualTo("RETURNED_GOODS");
                assertThat(saved.getStatus()).isEqualTo(CreditMemoStatus.POSTED);

                // Verify GL posting was called
                verify(glPostingService).postCreditMemoReversal(
                                any(UUID.class), eq(testRevenueAccountId), eq(testTaxAccountId), eq(testArAccountId),
                                any(BigDecimal.class), any(BigDecimal.class), anyString(), eq(false), eq(null));

                // Verify invoice was updated
                verify(invoiceServiceClient).applyCreditMemo(eq(testInvoiceId), any(ApplyCreditMemoRequest.class));
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

                ApplyCreditMemoResponse partialResponse = ApplyCreditMemoResponse.builder()
                                .invoiceId(testInvoiceId)
                                .balanceBefore(new BigDecimal("110.00"))
                                .balanceAfter(new BigDecimal("82.50"))
                                .status(InvoiceStatus.OPEN)
                                .creditMemoApplied(new BigDecimal("27.50"))
                                .build();

                when(invoiceServiceClient.getInvoiceDetails(testInvoiceId)).thenReturn(testInvoice);
                when(creditMemoRepository.save(any(CreditMemo.class))).thenReturn(savedMemo);
                when(invoiceServiceClient.applyCreditMemo(eq(testInvoiceId), any(ApplyCreditMemoRequest.class)))
                                .thenReturn(partialResponse);

                // When
                CreditMemoResponse response = service.createCreditMemo(testRequest, "test-user");

                // Then
                assertThat(response.getTaxAmountReversed()).isEqualByComparingTo("2.50");
                assertThat(response.getTotalAmount()).isEqualByComparingTo("27.50");
                assertThat(response.getInvoiceBalanceAfter()).isEqualByComparingTo("82.50");
        }

        @Test
        @DisplayName("Should throw exception when credit amount exceeds invoice balance")
        void testCreateCreditMemo_AmountExceedsBalance() {
                // Given - invoice balance is $110.00
                testRequest.setCreditAmount(new BigDecimal("200.00"));
                when(invoiceServiceClient.getInvoiceDetails(testInvoiceId)).thenReturn(testInvoice);

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
                // Given - invoice balance is $110.00
                // Under old validation, only the creditAmount (without tax) was compared to
                // balanceDue.
                // With a 10% tax reversal, we need a creditAmount such that:
                // creditAmount + (creditAmount * 0.10) > balanceDue
                // Setting creditAmount to $105.00 gives us:
                // - creditAmount: $105.00
                // - taxReversed: $10.50 (10% of 105)
                // - total: $115.50, which exceeds the $110.00 balance
                testRequest.setCreditAmount(new BigDecimal("105.00"));
                when(invoiceServiceClient.getInvoiceDetails(testInvoiceId)).thenReturn(testInvoice);

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
                // Given - invoice is fully paid (totalAmount = totalPaid, subtotal = 0)
                testInvoice = InvoiceDetails.builder()
                                .invoiceId(testInvoiceId)
                                .customerId(testCustomerId)
                                .status(InvoiceStatus.PAID_IN_FULL)
                                .totalAmount(new BigDecimal("110.00"))
                                .totalPaid(new BigDecimal("110.00"))
                                .balanceDue(BigDecimal.ZERO)
                                .currency("USD")
                                .build();
                when(invoiceServiceClient.getInvoiceDetails(testInvoiceId)).thenReturn(testInvoice);

                // When / Then - should fail fast with 409 before attempting division
                assertThatThrownBy(() -> service.createCreditMemo(testRequest, "test-user"))
                                .isInstanceOf(ResponseStatusException.class)
                                .hasMessageContaining("Invoice has no remaining balance to credit")
                                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("Should throw exception when invoice is voided")
        void testCreateCreditMemo_VoidedInvoice() {
                // Given
                testInvoice = InvoiceDetails.builder()
                                .invoiceId(testInvoiceId)
                                .customerId(testCustomerId)
                                .status(InvoiceStatus.VOIDED)
                                .totalAmount(new BigDecimal("110.00"))
                                .balanceDue(new BigDecimal("110.00"))
                                .currency("USD")
                                .build();
                when(invoiceServiceClient.getInvoiceDetails(testInvoiceId)).thenReturn(testInvoice);

                // When / Then
                assertThatThrownBy(() -> service.createCreditMemo(testRequest, "test-user"))
                                .isInstanceOf(ResponseStatusException.class)
                                .hasMessageContaining("VOIDED invoices")
                                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("Should throw exception when invoice service unavailable")
        void testCreateCreditMemo_InvoiceServiceUnavailable() {
                // Given
                when(invoiceServiceClient.getInvoiceDetails(testInvoiceId))
                                .thenThrow(new InvoiceServiceException("Service unavailable", 503));

                // When / Then
                assertThatThrownBy(() -> service.createCreditMemo(testRequest, "test-user"))
                                .isInstanceOf(ResponseStatusException.class)
                                .hasMessageContaining("unavailable")
                                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("Should detect and flag prior period adjustment")
        void testCreateCreditMemo_PriorPeriodAdjustment() {
                // Given - invoice from prior period
                Instant priorPeriodDate = Instant.now(TEST_CLOCK).minusSeconds(2592000); // 30 days ago
                testInvoice = InvoiceDetails.builder()
                                .invoiceId(testInvoiceId)
                                .customerId(testCustomerId)
                                .status(InvoiceStatus.OPEN)
                                .totalAmount(new BigDecimal("110.00"))
                                .balanceDue(new BigDecimal("110.00"))
                                .currency("USD")
                                .invoiceDate(priorPeriodDate)
                                .build();

                testCreditMemo.setPriorPeriodAdjustment(true);
                testCreditMemo.setOriginalPeriodId("2026-01");

                when(invoiceServiceClient.getInvoiceDetails(testInvoiceId)).thenReturn(testInvoice);
                when(periodService.isPriorPeriod(priorPeriodDate)).thenReturn(true);
                when(periodService.getPeriodIdForDate(priorPeriodDate)).thenReturn("2026-01");
                when(creditMemoRepository.save(any(CreditMemo.class))).thenReturn(testCreditMemo);
                when(invoiceServiceClient.applyCreditMemo(eq(testInvoiceId), any(ApplyCreditMemoRequest.class)))
                                .thenReturn(testInvoiceResponse);

                // When
                CreditMemoResponse response = service.createCreditMemo(testRequest, "test-user");

                // Then
                assertThat(response.getPriorPeriodAdjustment()).isTrue();
                assertThat(response.getOriginalPeriodId()).isEqualTo("2026-01");

                // Verify GL posting was called with prior period flag
                verify(glPostingService).postCreditMemoReversal(
                                any(UUID.class), any(UUID.class), any(UUID.class), any(UUID.class),
                                any(BigDecimal.class), any(BigDecimal.class), anyString(), eq(true), eq("2026-01"));
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

                when(creditMemoRepository.findByOriginalInvoiceId(testInvoiceId, pageable)).thenReturn(page);

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

                when(creditMemoRepository.findByStatus(CreditMemoStatus.POSTED, pageable)).thenReturn(page);

                // When
                Page<CreditMemoResponse> result = service.listCreditMemos(null, null, CreditMemoStatus.POSTED,
                                pageable);

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
                when(invoiceServiceClient.getInvoiceDetails(testInvoiceId)).thenReturn(testInvoice);

                // When
                CreditMemoResponse response = service.getCreditMemo(testCreditMemoId);

                // Then
                assertThat(response).isNotNull();
                assertThat(response.getCreditMemoId()).isEqualTo(testCreditMemoId);
                assertThat(response.getCreditAmount()).isEqualByComparingTo("50.00");
                assertThat(response.getInvoiceBalanceAfter()).isEqualByComparingTo("110.00");

                verify(creditMemoRepository).findById(testCreditMemoId);
                verify(invoiceServiceClient).getInvoiceDetails(testInvoiceId);
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
                String[] reasonCodes = {
                                "RETURNED_GOODS",
                                "PRICING_ERROR",
                                "SERVICE_CREDIT",
                                "BILLING_ERROR",
                                "DAMAGED_GOODS"
                };

                when(invoiceServiceClient.getInvoiceDetails(testInvoiceId)).thenReturn(testInvoice);
                when(invoiceServiceClient.applyCreditMemo(eq(testInvoiceId), any(ApplyCreditMemoRequest.class)))
                                .thenReturn(testInvoiceResponse);

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
                when(invoiceServiceClient.getInvoiceDetails(testInvoiceId)).thenReturn(testInvoice);
                when(creditMemoRepository.save(any(CreditMemo.class))).thenReturn(testCreditMemo);
                when(invoiceServiceClient.applyCreditMemo(eq(testInvoiceId), any(ApplyCreditMemoRequest.class)))
                                .thenReturn(testInvoiceResponse);

                // When
                CreditMemoResponse response = service.createCreditMemo(testRequest, "test-user");

                // Then
                assertThat(response.getJustificationNote()).isNull();
        }
}
