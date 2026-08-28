package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.dto.UnbalancedEntryException;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.event.JournalEntryReversed;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.JournalEntryNotReversibleException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for JournalEntryService
 *
 * Tests journal entry lifecycle (DRAFT → POSTED → REVERSED),
 * balance validation, GL account validation, and immutability constraints.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JournalEntryService Unit Tests")
class JournalEntryServiceTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private GLAccountServiceImpl glAccountService;

    @Mock
    private AccountingSequenceRepository sequenceRepository;

    @Mock
    private AccountingSequenceProvisioner sequenceProvisioner;

    @Mock
    private AccountingPeriodService accountingPeriodService;

    @Mock
    private AccountingPeriodRepository periodRepository;

    @Mock
    private AccountingConfigurationService configurationService;

    @Mock
    private AccountingAuditLogRepository auditLogRepository;

    @Mock
    private OutboxService outboxService;

    private JournalEntryServiceImpl service;

    private UUID testJournalEntryId;
    private UUID testGLAccountId1;
    private UUID testGLAccountId2;
    private UUID testSourceEventId;
    private LocalDateTime testTransactionDate;

    @BeforeEach
    void setUp() {
        // Real period gate (B2) wired against the mocked period repository /
        // config service, so the closed-period expectations below exercise
        // the production gate logic instead of a stubbed gate. The gate's
        // locked finder defaults to Optional.empty() (missing row = OPEN);
        // closed-period tests stub findWithLockByPeriodCode explicitly.
        lenient().when(configurationService.getHardLockDate()).thenReturn(Optional.empty());
        AccountingPeriodGate accountingPeriodGate = new AccountingPeriodGate(
                accountingPeriodService, periodRepository, configurationService, auditLogRepository);
        service = new JournalEntryServiceImpl(
                clock,
                journalEntryRepository,
                glAccountService,
                sequenceRepository,
                sequenceProvisioner,
                accountingPeriodService,
                accountingPeriodGate,
                auditLogRepository,
                outboxService);

        testJournalEntryId = UUID.randomUUID();
        testGLAccountId1 = UUID.randomUUID();
        testGLAccountId2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        testSourceEventId = UUID.randomUUID();
        testTransactionDate = LocalDateTime.now(TEST_CLOCK);
    }

    // ===== CREATE TESTS =====

    @Test
    @DisplayName("createJournalEntry - creates balanced entry successfully")
    void createJournalEntry_balanced_success() {
        // Arrange
        JournalEntryCreateRequest request = createBalancedRequest();

        doNothing().when(glAccountService).validateAccountForPosting(any(UUID.class), any(LocalDateTime.class));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        JournalEntryResponse result = service.createJournalEntry(request);

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
        // Arrange: JournalEntryCreateRequest never carries a journalEntryId, so every
        // create always generates a fresh one.
        JournalEntryCreateRequest request = createBalancedRequest();

        doNothing().when(glAccountService).validateAccountForPosting(any(UUID.class), any(LocalDateTime.class));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        JournalEntryResponse result = service.createJournalEntry(request);

        // Assert
        assertThat(result.getJournalEntryId()).isNotNull();
    }

    @Test
    @DisplayName("createJournalEntry - rejects unbalanced entry")
    void createJournalEntry_unbalanced_throwsException() {
        // Arrange
        JournalEntryCreateRequest request = createUnbalancedRequest();

        // Act & Assert
        assertThatThrownBy(() -> service.createJournalEntry(request))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("unbalanced");
    }

    @Test
    @DisplayName("createJournalEntry - rejects entry with no lines")
    void createJournalEntry_noLines_throwsException() {
        // Arrange
        JournalEntryCreateRequest request = JournalEntryCreateRequest.builder()
                .transactionDate(testTransactionDate)
                .lines(new ArrayList<>())
                .build();

        // Act & Assert
        assertThatThrownBy(() -> service.createJournalEntry(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must have at least one line");
    }

    @Test
    @DisplayName("createJournalEntry - rejects entry with invalid GL account")
    void createJournalEntry_invalidGLAccount_throwsException() {
        // Arrange
        JournalEntryCreateRequest request = createBalancedRequest();
        doThrow(new IllegalArgumentException("GL account not active"))
                .when(glAccountService)
                .validateAccountForPosting(any(UUID.class), any(LocalDateTime.class));

        // Act & Assert
        assertThatThrownBy(() -> service.createJournalEntry(request))
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
        JournalEntryResponse result = service.getJournalEntry(testJournalEntryId);

        // Assert
        assertThat(result.getJournalEntryId()).isEqualTo(entry.getJournalEntryId());
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

    @Test
    @DisplayName("getJournalTraceability - returns related entries for source event")
    void getJournalTraceability_withSourceEvent_success() {
        // Arrange
        JournalEntry entry = createBalancedEntry();
        UUID relatedId = UUID.randomUUID();
        JournalEntry related = createBalancedEntry();
        related.setJournalEntryId(relatedId);

        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(entry));
        when(journalEntryRepository.findBySourceEvent(testSourceEventId)).thenReturn(List.of(entry, related));
        when(journalEntryRepository.findByReversalReference(testJournalEntryId)).thenReturn(Optional.empty());

        // Act
        var traceability = service.getJournalTraceability(testJournalEntryId);

        // Assert
        assertThat(traceability.getJournalEntryId()).isEqualTo(testJournalEntryId);
        assertThat(traceability.getSourceEventId()).isEqualTo(testSourceEventId);
        assertThat(traceability.getJournalEntry()).isNotNull();
        assertThat(traceability.getRelatedJournalEntries()).hasSize(2);
        assertThat(traceability.getRelatedJournalEntries())
                .extracting(j -> j.getJournalEntryId())
                .containsExactly(testJournalEntryId, relatedId);
    }

    @Test
    @DisplayName("getJournalTraceability - resolves original entry via the reversal's linkage")
    void getJournalTraceability_reversalEntry_resolvesOriginalViaLinkage() {
        // Arrange: the inspected entry is a reversal whose reversalJournalEntry
        // link points at the original it reverses (A3 semantics).
        JournalEntry reversalEntry = createBalancedEntry();
        UUID originalId = UUID.randomUUID();
        JournalEntry originalEntry = createBalancedEntry();
        originalEntry.setJournalEntryId(originalId);
        reversalEntry.setReversalJournalEntryId(originalId);

        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(reversalEntry));
        when(journalEntryRepository.findById(originalId)).thenReturn(Optional.of(originalEntry));
        when(journalEntryRepository.findBySourceEvent(testSourceEventId))
                .thenReturn(List.of(reversalEntry, originalEntry));
        when(journalEntryRepository.findByReversalReference(testJournalEntryId)).thenReturn(Optional.empty());

        // Act
        var traceability = service.getJournalTraceability(testJournalEntryId);

        // Assert
        assertThat(traceability.getOriginalJournalEntry()).isNotNull();
        assertThat(traceability.getOriginalJournalEntry().getJournalEntryId()).isEqualTo(originalId);
    }

    @Test
    @DisplayName("getJournalTraceability - resolves reversal entry via reversal reference fallback")
    void getJournalTraceability_originalEntry_resolvesReversalViaReference() {
        // Arrange: the inspected entry is a reversed original without a
        // populated reversedByJournalEntry link; the fallback finds the entry
        // whose reversalJournalEntry points here.
        JournalEntry originalEntry = createBalancedEntry();
        UUID reversalId = UUID.randomUUID();
        JournalEntry reversalEntry = createBalancedEntry();
        reversalEntry.setJournalEntryId(reversalId);
        reversalEntry.setReversalJournalEntryId(testJournalEntryId);

        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(originalEntry));
        when(journalEntryRepository.findBySourceEvent(testSourceEventId))
                .thenReturn(List.of(originalEntry, reversalEntry));
        when(journalEntryRepository.findByReversalReference(testJournalEntryId)).thenReturn(Optional.of(reversalEntry));

        // Act
        var traceability = service.getJournalTraceability(testJournalEntryId);

        // Assert
        assertThat(traceability.getReversalJournalEntry()).isNotNull();
        assertThat(traceability.getReversalJournalEntry().getJournalEntryId()).isEqualTo(reversalId);
        assertThat(traceability.getOriginalJournalEntry()).isNull();
    }

    // ===== UPDATE TESTS =====

    @Test
    @DisplayName("updateJournalEntry - updates draft entry successfully")
    void updateJournalEntry_draft_success() {
        // Arrange
        JournalEntry existingEntry = createBalancedEntry();
        existingEntry.setStatus(JournalEntryStatus.DRAFT);

        JournalEntryCreateRequest updates = JournalEntryCreateRequest.builder()
                .description("Updated description")
                .lines(createBalancedLineRequests())
                .build();

        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(existingEntry));
        when(journalEntryRepository.saveAndFlush(any(JournalEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        JournalEntryResponse result = service.updateJournalEntry(testJournalEntryId, updates);

        // Assert
        assertThat(result.getDescription()).isEqualTo("Updated description");
        assertThat(result.getModifiedAt()).isNotNull();
        // Regression guard: the mapped response is built from the entity inside the same
        // transaction, and JPA @PreUpdate callbacks (e.g. modifiedBy) only run at flush —
        // save() alone would leave them stale (see updateJournalEntry_reflectsPostFlushModifiedBy).
        verify(journalEntryRepository).saveAndFlush(any(JournalEntry.class));
        verify(journalEntryRepository, never()).save(any(JournalEntry.class));
    }

    @Test
    @DisplayName("updateJournalEntry - modifiedBy reflects the acting user from the post-flush entity, "
            + "not whatever was on it before save")
    void updateJournalEntry_reflectsPostFlushModifiedBy() {
        // Arrange: entry carries a stale modifiedBy (e.g. left over from creation by a
        // different actor). save() returns the entity unchanged (no flush => @PreUpdate has
        // not run yet, mirroring real JPA behavior for an already-managed entity).
        // saveAndFlush() simulates @PreUpdate having fired: modifiedBy is overwritten to the
        // current actor ("SYSTEM", unauthenticated in this test).
        JournalEntry existingEntry = createBalancedEntry();
        existingEntry.setStatus(JournalEntryStatus.DRAFT);
        existingEntry.setModifiedBy("STALE_CREATOR");

        JournalEntryCreateRequest updates = JournalEntryCreateRequest.builder()
                .description("Updated description")
                .lines(createBalancedLineRequests())
                .build();

        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(existingEntry));
        lenient()
                .when(journalEntryRepository.save(any(JournalEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(journalEntryRepository.saveAndFlush(any(JournalEntry.class))).thenAnswer(invocation -> {
            JournalEntry entry = invocation.getArgument(0);
            entry.setModifiedBy("SYSTEM"); // what JournalEntry#onUpdate (@PreUpdate) would set
            return entry;
        });

        // Act
        JournalEntryResponse result = service.updateJournalEntry(testJournalEntryId, updates);

        // Assert
        assertThat(result.getModifiedBy()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("updateJournalEntry - rejects update to posted entry")
    void updateJournalEntry_posted_throwsException() {
        // Arrange
        JournalEntry existingEntry = createBalancedEntry();
        existingEntry.setStatus(JournalEntryStatus.POSTED);

        JournalEntryCreateRequest updates = JournalEntryCreateRequest.builder()
                .description("Updated description")
                .build();

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
        when(journalEntryRepository.saveAndFlush(any(JournalEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // Period gate (B2): no stub needed — the gate's locked finder
        // defaults to Optional.empty(), and a missing row counts as OPEN.

        // Sequence row for the entry's transaction month (2024-01, fixed clock)
        AccountingSequence sequence = new AccountingSequence();
        sequence.setScopeKey("JE-202401");
        sequence.setNextValue(5L);
        when(sequenceRepository.findByScopeKey("JE-202401")).thenReturn(Optional.of(sequence));

        // Act
        JournalEntryResponse result = service.postJournalEntry(testJournalEntryId);

        // Assert
        assertThat(result.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(result.getPostedAt()).isNotNull();
        assertThat(result.getModifiedAt()).isNotNull();
        assertThat(result.getEntryNumber()).isEqualTo("JE-202401-5");
        assertThat(sequence.getNextValue()).isEqualTo(6L);
        // Regression guard: see postJournalEntry_reflectsPostFlushModifiedBy — save() alone
        // would return the entity before @PreUpdate (modifiedBy) has run.
        verify(journalEntryRepository).saveAndFlush(any(JournalEntry.class));
        verify(journalEntryRepository, never()).save(any(JournalEntry.class));
    }

    @Test
    @DisplayName("postJournalEntry - modifiedBy reflects the acting user from the post-flush entity, "
            + "not whatever was on it before save")
    void postJournalEntry_reflectsPostFlushModifiedBy() {
        // Arrange: same simulation as updateJournalEntry_reflectsPostFlushModifiedBy — save()
        // returns the entity unchanged (pre-flush), saveAndFlush() simulates @PreUpdate having
        // set modifiedBy to the current actor.
        JournalEntry entry = createBalancedEntry();
        entry.setStatus(JournalEntryStatus.DRAFT);
        entry.setModifiedBy("STALE_CREATOR");

        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(entry));
        doNothing().when(glAccountService).validateAccountForPosting(any(UUID.class), any(LocalDateTime.class));
        lenient()
                .when(journalEntryRepository.save(any(JournalEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(journalEntryRepository.saveAndFlush(any(JournalEntry.class))).thenAnswer(invocation -> {
            JournalEntry saved = invocation.getArgument(0);
            saved.setModifiedBy("SYSTEM"); // what JournalEntry#onUpdate (@PreUpdate) would set
            return saved;
        });
        AccountingSequence sequence = new AccountingSequence();
        sequence.setScopeKey("JE-202401");
        sequence.setNextValue(5L);
        when(sequenceRepository.findByScopeKey("JE-202401")).thenReturn(Optional.of(sequence));

        // Act
        JournalEntryResponse result = service.postJournalEntry(testJournalEntryId);

        // Assert
        assertThat(result.getModifiedBy()).isEqualTo("SYSTEM");
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

    // ===== REVERSE TESTS (story A3, issue #943) =====

    private JournalEntry arrangePostedOriginalForReversal() {
        JournalEntry original = createBalancedEntry();
        original.setStatus(JournalEntryStatus.POSTED);
        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(original));
        return original;
    }

    private void arrangeReversalPersistence() {
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(journalEntryRepository.markReversed(
                        eq(testJournalEntryId), any(JournalEntry.class), any(Instant.class), eq("SYSTEM")))
                .thenReturn(1);

        // Sequence row for the reversal's transaction month (2024-01, fixed clock)
        AccountingSequence sequence = new AccountingSequence();
        sequence.setScopeKey("JE-202401");
        sequence.setNextValue(7L);
        when(sequenceRepository.findByScopeKey("JE-202401")).thenReturn(Optional.of(sequence));
    }

    @Test
    @DisplayName("reverseJournalEntry - creates numbered reversal, flips original, audits with actor")
    void reverseJournalEntry_posted_success() {
        // Arrange
        JournalEntry original = arrangePostedOriginalForReversal();
        arrangeReversalPersistence();
        when(accountingPeriodService.isPeriodOpen(any(LocalDate.class))).thenReturn(true);

        // Act
        JournalEntryResponse reversal = service.reverseJournalEntry(testJournalEntryId, "CORRECTION", null);

        // Assert - reversal entry: immediately POSTED, own number, linked back
        assertThat(reversal).isNotNull();
        assertThat(reversal.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(reversal.getEntryNumber()).isEqualTo("JE-202401-7");
        assertThat(reversal.getDescription()).contains("REVERSAL of");
        assertThat(reversal.getDescription()).contains("CORRECTION");
        assertThat(reversal.getReversalJournalEntryId()).isEqualTo(testJournalEntryId);
        assertThat(reversal.getTransactionDate())
                .as("original's period is open, so its transaction date is kept")
                .isEqualTo(testTransactionDate);
        assertThat(reversal.getLines()).hasSize(2);

        // Verify debits and credits are swapped
        JournalEntryResponse.JournalEntryLineResponse reversalLine1 =
                reversal.getLines().get(0);
        JournalEntryLine originalLine1 = original.getLines().get(0);
        assertThat(reversalLine1.getDebitAmount()).isEqualTo(originalLine1.getCreditAmount());
        assertThat(reversalLine1.getCreditAmount()).isEqualTo(originalLine1.getDebitAmount());

        // Verify the original flip went through the guarded update
        verify(journalEntryRepository)
                .markReversed(eq(testJournalEntryId), any(JournalEntry.class), any(Instant.class), eq("SYSTEM"));

        // The reversal is a genuinely new entity (@PrePersist runs synchronously on save(),
        // unlike @PreUpdate on an already-managed entity), so plain save() is sufficient here —
        // no saveAndFlush needed, unlike updateJournalEntry/postJournalEntry.
        verify(journalEntryRepository).save(any(JournalEntry.class));
        verify(journalEntryRepository, never()).saveAndFlush(any(JournalEntry.class));

        // Verify the period row exists for the reversal date
        verify(accountingPeriodService).ensurePeriodExists(testTransactionDate.toLocalDate());

        // Verify the audit row carries actor and transition
        ArgumentCaptor<AccountingAuditLog> auditCaptor = ArgumentCaptor.forClass(AccountingAuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        AccountingAuditLog audit = auditCaptor.getValue();
        assertThat(audit.getEntityType()).isEqualTo("JOURNAL_ENTRY");
        assertThat(audit.getEntityId()).isEqualTo(testJournalEntryId);
        assertThat(audit.getOperation()).isEqualTo("REVERSE");
        assertThat(audit.getUserId()).isEqualTo("SYSTEM");
        assertThat(audit.getJustification()).isEqualTo("CORRECTION");
        assertThat(audit.getOldValue()).isEqualTo("POSTED");
        assertThat(audit.getNewValue()).startsWith("REVERSED by ");

        // Verify the outbox domain event in the same transaction
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outboxService)
                .saveToOutbox(
                        any(UUID.class),
                        eq("JournalEntry"),
                        eq(testJournalEntryId),
                        eq("JournalEntryReversed"),
                        eventCaptor.capture());
        JournalEntryReversed event = (JournalEntryReversed) eventCaptor.getValue();
        assertThat(event.originalJournalEntryId()).isEqualTo(testJournalEntryId);
        assertThat(event.reversalJournalEntryId()).isEqualTo(reversal.getJournalEntryId());
        assertThat(event.reversalEntryNumber()).isEqualTo("JE-202401-7");
        assertThat(event.reversalDate()).isEqualTo(testTransactionDate.toLocalDate());
        assertThat(event.reason()).isEqualTo("CORRECTION");
        assertThat(event.actor()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("reverseJournalEntry - rejects reversal of draft entry (JE_NOT_POSTED)")
    void reverseJournalEntry_draft_throwsException() {
        // Arrange
        JournalEntry entry = createBalancedEntry();
        entry.setStatus(JournalEntryStatus.DRAFT);
        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(entry));

        // Act & Assert
        assertThatThrownBy(() -> service.reverseJournalEntry(testJournalEntryId, "CORRECTION", null))
                .isInstanceOf(JournalEntryNotReversibleException.class)
                .hasMessageContaining("Cannot reverse DRAFT")
                .extracting(ex -> ((JournalEntryNotReversibleException) ex).getCurrentStatus())
                .isEqualTo(JournalEntryStatus.DRAFT);
    }

    @Test
    @DisplayName("reverseJournalEntry - rejects double reversal (JE_ALREADY_REVERSED)")
    void reverseJournalEntry_alreadyReversed_throwsException() {
        // Arrange
        JournalEntry entry = createBalancedEntry();
        entry.setStatus(JournalEntryStatus.REVERSED);
        when(journalEntryRepository.findById(testJournalEntryId)).thenReturn(Optional.of(entry));

        // Act & Assert
        assertThatThrownBy(() -> service.reverseJournalEntry(testJournalEntryId, "CORRECTION", null))
                .isInstanceOf(JournalEntryNotReversibleException.class)
                .hasMessageContaining("Cannot reverse REVERSED")
                .extracting(ex -> ((JournalEntryNotReversibleException) ex).getCurrentStatus())
                .isEqualTo(JournalEntryStatus.REVERSED);
    }

    @Test
    @DisplayName("reverseJournalEntry - lost concurrent race (guard update count 0) surfaces as already reversed")
    void reverseJournalEntry_raceLost_throwsAlreadyReversed() {
        // Arrange: status check passes but the conditional UPDATE matches no
        // row because a concurrent reversal committed first.
        arrangePostedOriginalForReversal();
        when(accountingPeriodService.isPeriodOpen(any(LocalDate.class))).thenReturn(true);
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AccountingSequence sequence = new AccountingSequence();
        sequence.setScopeKey("JE-202401");
        sequence.setNextValue(1L);
        when(sequenceRepository.findByScopeKey("JE-202401")).thenReturn(Optional.of(sequence));
        when(journalEntryRepository.markReversed(
                        eq(testJournalEntryId), any(JournalEntry.class), any(Instant.class), eq("SYSTEM")))
                .thenReturn(0);

        // Act & Assert
        assertThatThrownBy(() -> service.reverseJournalEntry(testJournalEntryId, "CORRECTION", null))
                .isInstanceOf(JournalEntryNotReversibleException.class)
                .extracting(ex -> ((JournalEntryNotReversibleException) ex).getCurrentStatus())
                .isEqualTo(JournalEntryStatus.REVERSED);
        verify(auditLogRepository, never()).save(any(AccountingAuditLog.class));
        verify(outboxService, never()).saveToOutbox(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reverseJournalEntry - explicit reversalDate in an open period dates the reversal")
    void reverseJournalEntry_explicitOpenDate_used() {
        // Arrange
        arrangePostedOriginalForReversal();
        LocalDate explicitDate = LocalDate.of(2024, 1, 20);
        // Gate: missing period row (locked finder default) counts as OPEN.
        arrangeReversalPersistence();

        // Act
        JournalEntryResponse reversal = service.reverseJournalEntry(testJournalEntryId, "CORRECTION", explicitDate);

        // Assert
        assertThat(reversal.getTransactionDate()).isEqualTo(explicitDate.atStartOfDay());
        verify(accountingPeriodService).ensurePeriodExists(explicitDate);
    }

    @Test
    @DisplayName("reverseJournalEntry - explicit reversalDate in a closed period rejected (PERIOD_CLOSED)")
    void reverseJournalEntry_explicitClosedDate_throwsPeriodClosed() {
        // Arrange
        arrangePostedOriginalForReversal();
        LocalDate explicitDate = LocalDate.of(2023, 6, 15);
        when(periodRepository.findWithLockByPeriodCode("2023-06")).thenReturn(Optional.of(closedPeriod("2023-06")));

        // Act & Assert
        assertThatThrownBy(() -> service.reverseJournalEntry(testJournalEntryId, "CORRECTION", explicitDate))
                .isInstanceOf(AccountingPeriodClosedException.class)
                .hasMessageContaining("2023-06");
        verify(journalEntryRepository, never()).save(any(JournalEntry.class));
    }

    @Test
    @DisplayName("reverseJournalEntry - default date falls to current date when original's period is closed")
    void reverseJournalEntry_defaultDate_fallsToCurrentWhenOriginalPeriodClosed() {
        // Arrange: original dated in a closed month; current month (fixed
        // clock 2024-01) open.
        JournalEntry original = arrangePostedOriginalForReversal();
        original.setTransactionDate(LocalDateTime.of(2023, 11, 15, 10, 30));
        when(accountingPeriodService.isPeriodOpen(LocalDate.of(2023, 11, 15))).thenReturn(false);
        // Gate: current month has no period row (locked finder default = OPEN).
        arrangeReversalPersistence();

        // Act
        JournalEntryResponse reversal = service.reverseJournalEntry(testJournalEntryId, "CORRECTION", null);

        // Assert: reversal dated now (fixed clock), numbered in the current month
        assertThat(reversal.getTransactionDate()).isEqualTo(LocalDateTime.now(TEST_CLOCK));
        assertThat(reversal.getEntryNumber()).isEqualTo("JE-202401-7");
        verify(accountingPeriodService).ensurePeriodExists(LocalDate.now(TEST_CLOCK));
    }

    @Test
    @DisplayName("reverseJournalEntry - default date rejected when both original and current periods are closed")
    void reverseJournalEntry_defaultDate_bothPeriodsClosed_throwsPeriodClosed() {
        // Arrange
        JournalEntry original = arrangePostedOriginalForReversal();
        original.setTransactionDate(LocalDateTime.of(2023, 11, 15, 10, 30));
        // Date resolution: the original's month is closed, so it falls to now.
        when(accountingPeriodService.isPeriodOpen(any(LocalDate.class))).thenReturn(false);
        // Gate: the current (fixed clock 2024-01) month is CLOSED too.
        when(periodRepository.findWithLockByPeriodCode("2024-01")).thenReturn(Optional.of(closedPeriod("2024-01")));

        // Act & Assert
        assertThatThrownBy(() -> service.reverseJournalEntry(testJournalEntryId, "CORRECTION", null))
                .isInstanceOf(AccountingPeriodClosedException.class)
                .hasMessageContaining("2024-01");
        verify(journalEntryRepository, never()).save(any(JournalEntry.class));
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
        Page<JournalEntryResponse> result = service.listJournalEntries(pageable, null);

        // Assert
        assertThat(result.getContent()).hasSize(2);
        verify(journalEntryRepository).findAll(pageable);
    }

    @Test
    @DisplayName("listJournalEntries - blank entryNumber filter is treated as no filter")
    void listJournalEntries_blankEntryNumberFilter_ignored() {
        // Arrange
        Page<JournalEntry> page = new PageImpl<>(List.of(createBalancedEntry()));
        Pageable pageable = PageRequest.of(0, 10);

        when(journalEntryRepository.findAll(pageable)).thenReturn(page);

        // Act
        Page<JournalEntryResponse> result = service.listJournalEntries(pageable, "  ");

        // Assert
        assertThat(result.getContent()).hasSize(1);
        verify(journalEntryRepository).findAll(pageable);
    }

    @Test
    @DisplayName("listJournalEntries - entryNumber filter delegates to exact-match query")
    void listJournalEntries_entryNumberFilter_returnsMatch() {
        // Arrange
        JournalEntry posted = createBalancedEntry();
        posted.setStatus(JournalEntryStatus.POSTED);
        posted.setEntryNumber("JE-202607-1");
        Pageable pageable = PageRequest.of(0, 10);

        when(journalEntryRepository.findByEntryNumber("JE-202607-1", pageable))
                .thenReturn(new PageImpl<>(List.of(posted)));

        // Act
        Page<JournalEntryResponse> result = service.listJournalEntries(pageable, "JE-202607-1");

        // Assert
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getEntryNumber()).isEqualTo("JE-202607-1");
        verify(journalEntryRepository).findByEntryNumber("JE-202607-1", pageable);
    }

    @Test
    @DisplayName("listPostedEntries - returns only posted entries")
    void listPostedEntries_success() {
        // Arrange
        List<JournalEntry> entries = List.of(createBalancedEntry());
        Page<JournalEntry> page = new PageImpl<>(entries);
        Pageable pageable = PageRequest.of(0, 10);

        when(journalEntryRepository.findByStatus(JournalEntryStatus.POSTED, pageable))
                .thenReturn(page);

        // Act
        Page<JournalEntryResponse> result = service.listPostedEntries(pageable);

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
        List<JournalEntryResponse> result = service.findByStatus(JournalEntryStatus.DRAFT);

        // Assert
        assertThat(result).hasSize(1);
        verify(journalEntryRepository).findByStatus(JournalEntryStatus.DRAFT);
    }

    // ===== HELPER METHODS =====

    private static AccountingPeriod closedPeriod(String periodCode) {
        AccountingPeriod period = new AccountingPeriod();
        period.setPeriodCode(periodCode);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        return period;
    }

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

    private JournalEntryCreateRequest createBalancedRequest() {
        return JournalEntryCreateRequest.builder()
                .transactionDate(testTransactionDate)
                .description("Test entry")
                .sourceEventId(testSourceEventId)
                .lines(createBalancedLineRequests())
                .build();
    }

    private List<JournalEntryCreateRequest.JournalEntryLineRequest> createBalancedLineRequests() {
        return List.of(
                JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                        .glAccountId(testGLAccountId1)
                        .debitAmount(new BigDecimal("100.00"))
                        .creditAmount(BigDecimal.ZERO)
                        .description("Debit line")
                        .build(),
                JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                        .glAccountId(testGLAccountId2)
                        .debitAmount(BigDecimal.ZERO)
                        .creditAmount(new BigDecimal("100.00"))
                        .description("Credit line")
                        .build());
    }

    private JournalEntryCreateRequest createUnbalancedRequest() {
        return JournalEntryCreateRequest.builder()
                .transactionDate(testTransactionDate)
                .description("Unbalanced entry")
                .lines(List.of(
                        JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                .glAccountId(testGLAccountId1)
                                .debitAmount(new BigDecimal("100.00"))
                                .creditAmount(BigDecimal.ZERO)
                                .build(),
                        JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                .glAccountId(testGLAccountId2)
                                .debitAmount(BigDecimal.ZERO)
                                .creditAmount(new BigDecimal("50.00")) // Unbalanced!
                                .build()))
                .build();
    }
}
