package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.JournalEntryStatus;

/**
 * Unit tests for GLPostingService
 * 
 * Tests GL posting operations for credit memos and payment applications
 * including entry creation, balance validation, and posting.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GLPostingService Unit Tests")
class GLPostingServiceTest {

    @Mock
    private JournalEntryService journalEntryService;

    @InjectMocks
    private GLPostingService service;

    private UUID testCreditMemoId;
    private UUID testPaymentApplicationId;
    private UUID testRevenueAccountId;
    private UUID testTaxAccountId;
    private UUID testArAccountId;
    private UUID testCashAccountId;
    private UUID testJournalEntryId;

    @BeforeEach
    void setUp() {
        testCreditMemoId = UUID.randomUUID();
        testPaymentApplicationId = UUID.randomUUID();
        testRevenueAccountId = UUID.randomUUID();
        testTaxAccountId = UUID.randomUUID();
        testArAccountId = UUID.randomUUID();
        testCashAccountId = UUID.randomUUID();
        testJournalEntryId = UUID.randomUUID();
    }

    // ===== CREDIT MEMO POSTING TESTS =====

    @Test
    @DisplayName("postCreditMemoReversal - creates balanced 3-line entry")
    void postCreditMemoReversal_createsBalancedEntry() {
        // Arrange
        BigDecimal creditAmount = new BigDecimal("100.00");
        BigDecimal taxReversed = new BigDecimal("10.00");
        String description = "Credit memo reversal";
        
        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        when(journalEntryService.createJournalEntry(entryCaptor.capture())).thenAnswer(inv -> {
            JournalEntry entry = inv.getArgument(0);
            entry.setJournalEntryId(testJournalEntryId);
            return entry;
        });
        
        JournalEntry posted = new JournalEntry();
        posted.setJournalEntryId(testJournalEntryId);
        posted.setStatus(JournalEntryStatus.POSTED);
        when(journalEntryService.postJournalEntry(testJournalEntryId)).thenReturn(posted);

        // Act
        JournalEntry result = service.postCreditMemoReversal(
            testCreditMemoId,
            testRevenueAccountId,
            testTaxAccountId,
            testArAccountId,
            creditAmount,
            taxReversed,
            description,
            false,
            null
        );

        // Assert
        assertThat(result.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        
        JournalEntry createdEntry = entryCaptor.getValue();
        assertThat(createdEntry.getLines()).hasSize(3);
        assertThat(createdEntry.getSourceEventId()).isEqualTo(testCreditMemoId);
        
        // Verify debits
        BigDecimal totalDebits = createdEntry.getLines().stream()
            .map(JournalEntryLine::getDebitAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebits).isEqualByComparingTo(new BigDecimal("110.00"));
        
        // Verify credits
        BigDecimal totalCredits = createdEntry.getLines().stream()
            .map(JournalEntryLine::getCreditAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalCredits).isEqualByComparingTo(new BigDecimal("110.00"));
        
        verify(journalEntryService).createJournalEntry(any(JournalEntry.class));
        verify(journalEntryService).postJournalEntry(testJournalEntryId);
    }

    @Test
    @DisplayName("postCreditMemoReversal - revenue line has correct debit")
    void postCreditMemoReversal_revenueLineCorrect() {
        // Arrange
        BigDecimal creditAmount = new BigDecimal("100.00");
        BigDecimal taxReversed = new BigDecimal("10.00");
        
        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        when(journalEntryService.createJournalEntry(entryCaptor.capture())).thenAnswer(inv -> {
            JournalEntry entry = inv.getArgument(0);
            entry.setJournalEntryId(testJournalEntryId);
            return entry;
        });
        when(journalEntryService.postJournalEntry(any())).thenReturn(new JournalEntry());

        // Act
        service.postCreditMemoReversal(
            testCreditMemoId, testRevenueAccountId, testTaxAccountId, testArAccountId,
            creditAmount, taxReversed, "Test", false, null
        );

        // Assert
        JournalEntry entry = entryCaptor.getValue();
        JournalEntryLine revenueLine = entry.getLines().stream()
            .filter(line -> line.getGlAccountId().equals(testRevenueAccountId))
            .findFirst()
            .orElseThrow();
        
        assertThat(revenueLine.getDebitAmount()).isEqualByComparingTo(creditAmount);
        assertThat(revenueLine.getCreditAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("postCreditMemoReversal - tax line has correct debit")
    void postCreditMemoReversal_taxLineCorrect() {
        // Arrange
        BigDecimal creditAmount = new BigDecimal("100.00");
        BigDecimal taxReversed = new BigDecimal("10.00");
        
        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        when(journalEntryService.createJournalEntry(entryCaptor.capture())).thenAnswer(inv -> {
            JournalEntry entry = inv.getArgument(0);
            entry.setJournalEntryId(testJournalEntryId);
            return entry;
        });
        when(journalEntryService.postJournalEntry(any())).thenReturn(new JournalEntry());

        // Act
        service.postCreditMemoReversal(
            testCreditMemoId, testRevenueAccountId, testTaxAccountId, testArAccountId,
            creditAmount, taxReversed, "Test", false, null
        );

        // Assert
        JournalEntry entry = entryCaptor.getValue();
        JournalEntryLine taxLine = entry.getLines().stream()
            .filter(line -> line.getGlAccountId().equals(testTaxAccountId))
            .findFirst()
            .orElseThrow();
        
        assertThat(taxLine.getDebitAmount()).isEqualByComparingTo(taxReversed);
        assertThat(taxLine.getCreditAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("postCreditMemoReversal - AR line has correct credit")
    void postCreditMemoReversal_arLineCorrect() {
        // Arrange
        BigDecimal creditAmount = new BigDecimal("100.00");
        BigDecimal taxReversed = new BigDecimal("10.00");
        BigDecimal expectedTotal = creditAmount.add(taxReversed);
        
        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        when(journalEntryService.createJournalEntry(entryCaptor.capture())).thenAnswer(inv -> {
            JournalEntry entry = inv.getArgument(0);
            entry.setJournalEntryId(testJournalEntryId);
            return entry;
        });
        when(journalEntryService.postJournalEntry(any())).thenReturn(new JournalEntry());

        // Act
        service.postCreditMemoReversal(
            testCreditMemoId, testRevenueAccountId, testTaxAccountId, testArAccountId,
            creditAmount, taxReversed, "Test", false, null
        );

        // Assert
        JournalEntry entry = entryCaptor.getValue();
        JournalEntryLine arLine = entry.getLines().stream()
            .filter(line -> line.getGlAccountId().equals(testArAccountId))
            .findFirst()
            .orElseThrow();
        
        assertThat(arLine.getDebitAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(arLine.getCreditAmount()).isEqualByComparingTo(expectedTotal);
    }

    @Test
    @DisplayName("postCreditMemoReversal - includes prior period marker when flagged")
    void postCreditMemoReversal_priorPeriod_includesMarker() {
        // Arrange
        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        when(journalEntryService.createJournalEntry(entryCaptor.capture())).thenAnswer(inv -> {
            JournalEntry entry = inv.getArgument(0);
            entry.setJournalEntryId(testJournalEntryId);
            return entry;
        });
        when(journalEntryService.postJournalEntry(any())).thenReturn(new JournalEntry());

        // Act
        service.postCreditMemoReversal(
            testCreditMemoId, testRevenueAccountId, testTaxAccountId, testArAccountId,
            new BigDecimal("100.00"), new BigDecimal("10.00"),
            "Test", true, "2025-12"
        );

        // Assert
        JournalEntry entry = entryCaptor.getValue();
        assertThat(entry.getDescription()).contains("PRIOR PERIOD: 2025-12");
    }

    @Test
    @DisplayName("postCreditMemoReversal - excludes prior period marker when not flagged")
    void postCreditMemoReversal_currentPeriod_noMarker() {
        // Arrange
        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        when(journalEntryService.createJournalEntry(entryCaptor.capture())).thenAnswer(inv -> {
            JournalEntry entry = inv.getArgument(0);
            entry.setJournalEntryId(testJournalEntryId);
            return entry;
        });
        when(journalEntryService.postJournalEntry(any())).thenReturn(new JournalEntry());

        // Act
        service.postCreditMemoReversal(
            testCreditMemoId, testRevenueAccountId, testTaxAccountId, testArAccountId,
            new BigDecimal("100.00"), new BigDecimal("10.00"),
            "Test", false, null
        );

        // Assert
        JournalEntry entry = entryCaptor.getValue();
        assertThat(entry.getDescription()).doesNotContain("PRIOR PERIOD");
    }

    // ===== PAYMENT APPLICATION POSTING TESTS =====

    @Test
    @DisplayName("postPaymentApplication - creates balanced 2-line entry")
    void postPaymentApplication_createsBalancedEntry() {
        // Arrange
        BigDecimal amount = new BigDecimal("150.00");
        String description = "Payment received";
        
        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        when(journalEntryService.createJournalEntry(entryCaptor.capture())).thenAnswer(inv -> {
            JournalEntry entry = inv.getArgument(0);
            entry.setJournalEntryId(testJournalEntryId);
            return entry;
        });
        
        JournalEntry posted = new JournalEntry();
        posted.setJournalEntryId(testJournalEntryId);
        posted.setStatus(JournalEntryStatus.POSTED);
        when(journalEntryService.postJournalEntry(testJournalEntryId)).thenReturn(posted);

        // Act
        JournalEntry result = service.postPaymentApplication(
            testPaymentApplicationId,
            testCashAccountId,
            testArAccountId,
            amount,
            description
        );

        // Assert
        assertThat(result.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        
        JournalEntry createdEntry = entryCaptor.getValue();
        assertThat(createdEntry.getLines()).hasSize(2);
        assertThat(createdEntry.getSourceEventId()).isEqualTo(testPaymentApplicationId);
        
        // Verify balance
        BigDecimal totalDebits = createdEntry.getLines().stream()
            .map(JournalEntryLine::getDebitAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredits = createdEntry.getLines().stream()
            .map(JournalEntryLine::getCreditAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebits).isEqualByComparingTo(totalCredits);
        assertThat(totalDebits).isEqualByComparingTo(amount);
        
        verify(journalEntryService).createJournalEntry(any(JournalEntry.class));
        verify(journalEntryService).postJournalEntry(testJournalEntryId);
    }

    @Test
    @DisplayName("postPaymentApplication - cash line has correct debit")
    void postPaymentApplication_cashLineCorrect() {
        // Arrange
        BigDecimal amount = new BigDecimal("150.00");
        
        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        when(journalEntryService.createJournalEntry(entryCaptor.capture())).thenAnswer(inv -> {
            JournalEntry entry = inv.getArgument(0);
            entry.setJournalEntryId(testJournalEntryId);
            return entry;
        });
        when(journalEntryService.postJournalEntry(any())).thenReturn(new JournalEntry());

        // Act
        service.postPaymentApplication(
            testPaymentApplicationId, testCashAccountId, testArAccountId,
            amount, "Test"
        );

        // Assert
        JournalEntry entry = entryCaptor.getValue();
        JournalEntryLine cashLine = entry.getLines().stream()
            .filter(line -> line.getGlAccountId().equals(testCashAccountId))
            .findFirst()
            .orElseThrow();
        
        assertThat(cashLine.getDebitAmount()).isEqualByComparingTo(amount);
        assertThat(cashLine.getCreditAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("postPaymentApplication - AR line has correct credit")
    void postPaymentApplication_arLineCorrect() {
        // Arrange
        BigDecimal amount = new BigDecimal("150.00");
        
        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        when(journalEntryService.createJournalEntry(entryCaptor.capture())).thenAnswer(inv -> {
            JournalEntry entry = inv.getArgument(0);
            entry.setJournalEntryId(testJournalEntryId);
            return entry;
        });
        when(journalEntryService.postJournalEntry(any())).thenReturn(new JournalEntry());

        // Act
        service.postPaymentApplication(
            testPaymentApplicationId, testCashAccountId, testArAccountId,
            amount, "Test"
        );

        // Assert
        JournalEntry entry = entryCaptor.getValue();
        JournalEntryLine arLine = entry.getLines().stream()
            .filter(line -> line.getGlAccountId().equals(testArAccountId))
            .findFirst()
            .orElseThrow();
        
        assertThat(arLine.getDebitAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(arLine.getCreditAmount()).isEqualByComparingTo(amount);
    }

    @Test
    @DisplayName("postPaymentApplication - sets description correctly")
    void postPaymentApplication_setsDescription() {
        // Arrange
        String description = "Customer payment received";
        
        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        when(journalEntryService.createJournalEntry(entryCaptor.capture())).thenAnswer(inv -> {
            JournalEntry entry = inv.getArgument(0);
            entry.setJournalEntryId(testJournalEntryId);
            return entry;
        });
        when(journalEntryService.postJournalEntry(any())).thenReturn(new JournalEntry());

        // Act
        service.postPaymentApplication(
            testPaymentApplicationId, testCashAccountId, testArAccountId,
            new BigDecimal("100.00"), description
        );

        // Assert
        JournalEntry entry = entryCaptor.getValue();
        assertThat(entry.getDescription()).isEqualTo(description);
    }
}
