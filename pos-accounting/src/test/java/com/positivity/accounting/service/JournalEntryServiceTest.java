package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

import com.positivity.accounting.internal.dto.UnbalancedEntryException;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.repository.JournalEntryRepository;

/**
 * Unit tests for JournalEntryService
 * 
 * Tests journal entry lifecycle (DRAFT → POSTED → REVERSED),
 * balance validation, GL account validation, and immutability constraints.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JournalEntryService Unit Tests")
class JournalEntryServiceTest {

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private GLAccountService glAccountService;

    @InjectMocks
    private JournalEntryService service;

    private UUID testJournalEntryId;
    private UUID testGLAccountId1;
    private UUID testGLAccountId2;
    private UUID testSourceEventId;
    private LocalDateTime testTransactionDate;

    @BeforeEach
    void setUp() {
        testJournalEntryId = UUID.randomUUID();
        testGLAccountId1 = UUID.randomUUID();
        testGLAccountId2 = UUID.randomUUID();
        testSourceEventId = UUID.randomUUID();
        testTransactionDate = LocalDateTime.now();
    }

    // ===== CREATE TESTS =====

    @Test
    @DisplayName("createJournalEntry - creates balanced entry successfully")
    void createJournalEntry_balanced_success() {
        // Arrange
        JournalEntry entry = createBalancedEntry();
        
        doNothing().when(glAccountService).validateAccountForPosting(any(UUID.class), any(LocalDateTime.class));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        JournalEntry result = service.createJournalEntry(entry);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(JournalEntryStatus.DRAFT);
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getModifiedAt()).isNotNull();
        verify(journalEntryRepository).save(any(JournalEntry.class));
        verify(glAccountService).validateAccountForPosting(eq(testGLAccountId1), eq(testTransactionDate));
        verify(glAccountService).validateAccountForPosting(eq(testGLAccountId2), eq(testTransactionDate));
    }

    @Test
    @DisplayName("createJournalEntry - generates ID if not present")
    void createJournalEntry_generatesId() {
        // Arrange
        JournalEntry entry = createBalancedEntry();
        entry.setJournalEntryId(null);
        
        doNothing().when(glAccountService).validateAccountForPosting(any(UUID.class), any(LocalDateTime.class));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        JournalEntry result = service.createJournalEntry(entry);

        // Assert
        assertThat(result.getJournalEntryId()).isNotNull();
    }

    @Test
    @DisplayName("createJournalEntry - rejects unbalanced entry")
    void createJournalEntry_unbalanced_throwsException() {
        // Arrange
        JournalEntry entry = createUnbalancedEntry();

        // Act & Assert
        assertThatThrownBy(() -> service.createJournalEntry(entry))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("unbalanced");
    }

    @Test
    @DisplayName("createJournalEntry - rejects entry with no lines")
    void createJournalEntry_noLines_throwsException() {
        // Arrange
        JournalEntry entry = new JournalEntry();
        entry.setJournalEntryId(testJournalEntryId);
        entry.setTransactionDate(testTransactionDate);
        entry.setLines(new ArrayList<>());

        // Act & Assert
        assertThatThrownBy(() -> service.createJournalEntry(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must have at least one line");
    }

    @Test
    @DisplayName("createJournalEntry - rejects entry with invalid GL account")
    void createJournalEntry_invalidGLAccount_throwsException() {
        // Arrange
        JournalEntry entry = createBalancedEntry();
        doThrow(new IllegalArgumentException("GL account not active"))
                .when(glAccountService).validateAccountForPosting(any(UUID.class), any(LocalDateTime.class));

        // Act & Assert
        assertThatThrownBy(() -> service.createJournalEntry(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GL account not active");
    }

    // ===== GET TESTS =====

    @Test
    @DisplayName("getJournalEntry - returns existing entry")
    void getJournalEntry_found_success() {
        // Arrange
        JournalEntry entry = createBalancedEntry();
        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(entry));

        // Act
        JournalEntry result = service.getJournalEntry(testJournalEntryId);

        // Assert
        assertThat(result).isEqualTo(entry);
        verify(journalEntryRepository).findById(testJournalEntryId);
    }

    @Test
    @DisplayName("getJournalEntry - throws exception when not found")
    void getJournalEntry_notFound_throwsException() {
        // Arrange
        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.getJournalEntry(testJournalEntryId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Journal entry not found");
    }

    // ===== UPDATE TESTS =====

    @Test
    @DisplayName("updateJournalEntry - updates draft entry successfully")
    void updateJournalEntry_draft_success() {
        // Arrange
        JournalEntry existingEntry = createBalancedEntry();
        existingEntry.setStatus(JournalEntryStatus.DRAFT);
        
        JournalEntry updates = new JournalEntry();
        updates.setDescription("Updated description");
        updates.setLines(createBalancedLines());
        
        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(existingEntry));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        JournalEntry result = service.updateJournalEntry(testJournalEntryId, updates);

        // Assert
        assertThat(result.getDescription()).isEqualTo("Updated description");
        assertThat(result.getModifiedAt()).isNotNull();
        verify(journalEntryRepository).save(any(JournalEntry.class));
    }

    @Test
    @DisplayName("updateJournalEntry - rejects update to posted entry")
    void updateJournalEntry_posted_throwsException() {
        // Arrange
        JournalEntry existingEntry = createBalancedEntry();
        existingEntry.setStatus(JournalEntryStatus.POSTED);
        
        JournalEntry updates = new JournalEntry();
        updates.setDescription("Updated description");
        
        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(existingEntry));

        // Act & Assert
        assertThatThrownBy(() -> service.updateJournalEntry(testJournalEntryId, updates))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot update POSTED");
    }

    // ===== POST TESTS =====

    @Test
    @DisplayName("postJournalEntry - posts draft entry successfully")
    void postJournalEntry_draft_success() {
        // Arrange
        JournalEntry entry = createBalancedEntry();
        entry.setStatus(JournalEntryStatus.DRAFT);
        
        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(entry));
        doNothing().when(glAccountService).validateAccountForPosting(any(UUID.class), any(LocalDateTime.class));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        JournalEntry result = service.postJournalEntry(testJournalEntryId);

        // Assert
        assertThat(result.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(result.getPostedAt()).isNotNull();
        assertThat(result.getModifiedAt()).isNotNull();
        verify(journalEntryRepository).save(any(JournalEntry.class));
    }

    @Test
    @DisplayName("postJournalEntry - rejects already posted entry")
    void postJournalEntry_alreadyPosted_throwsException() {
        // Arrange
        JournalEntry entry = createBalancedEntry();
        entry.setStatus(JournalEntryStatus.POSTED);
        
        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(entry));

        // Act & Assert
        assertThatThrownBy(() -> service.postJournalEntry(testJournalEntryId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot post POSTED");
    }

    // ===== REVERSE TESTS =====

    @Test
    @DisplayName("reverseJournalEntry - creates reversal entry successfully")
    void reverseJournalEntry_posted_success() {
        // Arrange
        JournalEntry original = createBalancedEntry();
        original.setStatus(JournalEntryStatus.POSTED);
        
        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(original));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        JournalEntry reversal = service.reverseJournalEntry(testJournalEntryId, "CORRECTION");

        // Assert
        assertThat(reversal).isNotNull();
        assertThat(reversal.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(reversal.getDescription()).contains("Reversal of");
        assertThat(reversal.getDescription()).contains("CORRECTION");
        assertThat(reversal.getLines()).hasSize(2);
        
        // Verify debits and credits are swapped
        JournalEntryLine reversalLine1 = reversal.getLines().get(0);
        JournalEntryLine originalLine1 = original.getLines().get(0);
        assertThat(reversalLine1.getDebitAmount()).isEqualTo(originalLine1.getCreditAmount());
        assertThat(reversalLine1.getCreditAmount()).isEqualTo(originalLine1.getDebitAmount());
        
        verify(journalEntryRepository).save(any(JournalEntry.class));
    }

    @Test
    @DisplayName("reverseJournalEntry - rejects reversal of draft entry")
    void reverseJournalEntry_draft_throwsException() {
        // Arrange
        JournalEntry entry = createBalancedEntry();
        entry.setStatus(JournalEntryStatus.DRAFT);
        
        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(entry));

        // Act & Assert
        assertThatThrownBy(() -> service.reverseJournalEntry(testJournalEntryId, "CORRECTION"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot reverse DRAFT");
    }

    // ===== LIST TESTS =====

    @Test
    @DisplayName("listJournalEntries - returns paginated results")
    void listJournalEntries_success() {
        // Arrange
        List<JournalEntry> entries = List.of(createBalancedEntry(), createBalancedEntry());
        Page<JournalEntry> page = new PageImpl<>(entries);
        Pageable pageable = PageRequest.of(0, 10);
        
        when(journalEntryRepository.findAll(pageable)).thenReturn(page);

        // Act
        Page<JournalEntry> result = service.listJournalEntries(pageable);

        // Assert
        assertThat(result.getContent()).hasSize(2);
        verify(journalEntryRepository).findAll(pageable);
    }

    @Test
    @DisplayName("listPostedEntries - returns only posted entries")
    void listPostedEntries_success() {
        // Arrange
        List<JournalEntry> entries = List.of(createBalancedEntry());
        Page<JournalEntry> page = new PageImpl<>(entries);
        Pageable pageable = PageRequest.of(0, 10);
        
        when(journalEntryRepository.findByStatus(JournalEntryStatus.POSTED, pageable)).thenReturn(page);

        // Act
        Page<JournalEntry> result = service.listPostedEntries(pageable);

        // Assert
        assertThat(result.getContent()).hasSize(1);
        verify(journalEntryRepository).findByStatus(JournalEntryStatus.POSTED, pageable);
    }

    @Test
    @DisplayName("findByStatus - returns entries with specified status")
    void findByStatus_success() {
        // Arrange
        List<JournalEntry> entries = List.of(createBalancedEntry());
        when(journalEntryRepository.findByStatus(JournalEntryStatus.DRAFT)).thenReturn(entries);

        // Act
        List<JournalEntry> result = service.findByStatus(JournalEntryStatus.DRAFT);

        // Assert
        assertThat(result).hasSize(1);
        verify(journalEntryRepository).findByStatus(JournalEntryStatus.DRAFT);
    }

    // ===== HELPER METHODS =====

    private JournalEntry createBalancedEntry() {
        JournalEntry entry = new JournalEntry();
        entry.setJournalEntryId(testJournalEntryId);
        entry.setTransactionDate(testTransactionDate);
        entry.setDescription("Test entry");
        entry.setSourceEventId(testSourceEventId);
        entry.setLines(createBalancedLines());
        return entry;
    }

    private List<JournalEntryLine> createBalancedLines() {
        List<JournalEntryLine> lines = new ArrayList<>();
        
        JournalEntryLine line1 = new JournalEntryLine();
        line1.setLineId(UUID.randomUUID());
        line1.setJournalEntryId(testJournalEntryId);
        line1.setGlAccountId(testGLAccountId1);
        line1.setDebitAmount(new BigDecimal("100.00"));
        line1.setCreditAmount(BigDecimal.ZERO);
        line1.setDescription("Debit line");
        lines.add(line1);
        
        JournalEntryLine line2 = new JournalEntryLine();
        line2.setLineId(UUID.randomUUID());
        line2.setJournalEntryId(testJournalEntryId);
        line2.setGlAccountId(testGLAccountId2);
        line2.setDebitAmount(BigDecimal.ZERO);
        line2.setCreditAmount(new BigDecimal("100.00"));
        line2.setDescription("Credit line");
        lines.add(line2);
        
        return lines;
    }

    private JournalEntry createUnbalancedEntry() {
        JournalEntry entry = new JournalEntry();
        entry.setJournalEntryId(testJournalEntryId);
        entry.setTransactionDate(testTransactionDate);
        entry.setDescription("Unbalanced entry");
        
        List<JournalEntryLine> lines = new ArrayList<>();
        JournalEntryLine line1 = new JournalEntryLine();
        line1.setLineId(UUID.randomUUID());
        line1.setGlAccountId(testGLAccountId1);
        line1.setDebitAmount(new BigDecimal("100.00"));
        line1.setCreditAmount(BigDecimal.ZERO);
        lines.add(line1);
        
        JournalEntryLine line2 = new JournalEntryLine();
        line2.setLineId(UUID.randomUUID());
        line2.setGlAccountId(testGLAccountId2);
        line2.setDebitAmount(BigDecimal.ZERO);
        line2.setCreditAmount(new BigDecimal("50.00")); // Unbalanced!
        lines.add(line2);
        
        entry.setLines(lines);
        return entry;
    }
}
