package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.audit.repository.AuditTrailEntryRepository;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@code EventIngestionServiceImpl}'s {@code eventReference}
 * assignment (issue #1680): per-month {@code AE-{YYYYMM}-{n}} numbering
 * reusing the story A2 {@code accounting_sequence} counter machinery.
 *
 * <p>The {@code accounting_sequence} row returned by the mocked repository
 * is backed by a plain in-memory map keyed by scope, and the same
 * {@link AccountingSequence} instance is returned across calls for a given
 * scope — mirroring how a real {@code FOR UPDATE}-locked row is mutated
 * in-place and dirty-checked, without standing up a persistence context.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventIngestionService event reference assignment")
class EventIngestionServiceEventReferenceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private AccountingEventRepository accountingEventRepository;

    @Mock
    private ReprocessingAttemptHistoryRepository reprocessingAttemptHistoryRepository;

    @Mock
    private IdempotencyServiceImpl idempotencyService;

    @Mock
    private AuditTrailEntryRepository auditTrailEntryRepository;

    @Mock
    private PostingEngineOrchestrator postingEngineOrchestrator;

    @Mock
    private AccountingSequenceRepository sequenceRepository;

    @Mock
    private AccountingSequenceProvisioner sequenceProvisioner;

    @InjectMocks
    private EventIngestionServiceImpl service;

    private UUID testOrganizationId;
    private final Map<String, AccountingSequence> sequenceRows = new HashMap<>();

    @BeforeEach
    void setUp() {
        testOrganizationId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        when(sequenceRepository.findByScopeKey(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(sequenceRows.get((String) invocation.getArgument(0))));
        when(sequenceProvisioner.provision(anyString())).thenAnswer(invocation -> {
            String scopeKey = invocation.getArgument(0);
            AccountingSequence sequence = new AccountingSequence();
            sequence.setScopeKey(scopeKey);
            sequence.setNextValue(1L);
            sequenceRows.put(scopeKey, sequence);
            return sequence;
        });

        when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);
    }

    @Test
    @DisplayName("first event of a month is AE-{YYYYMM}-1, the next is -2")
    void submitEvent_AssignsSequentialReferencesWithinMonth() {
        Instant firstReceivedAt = Instant.parse("2026-09-05T10:00:00Z");
        Instant secondReceivedAt = Instant.parse("2026-09-20T08:00:00Z");

        when(accountingEventRepository.save(org.mockito.ArgumentMatchers.any(AccountingEvent.class)))
                .thenReturn(savedEvent(firstReceivedAt))
                .thenReturn(savedEvent(secondReceivedAt));

        AccountingEventResponse first = service.submitEvent(eventMap("first event"));
        AccountingEventResponse second = service.submitEvent(eventMap("second event"));

        assertThat(first.getEventReference()).isEqualTo("AE-202609-1");
        assertThat(second.getEventReference()).isEqualTo("AE-202609-2");
    }

    @Test
    @DisplayName("a second month opens its own scope starting at -1; scope month follows receivedAt")
    void submitEvent_NewMonthOpensIndependentScope() {
        Instant septemberReceivedAt = Instant.parse("2026-09-30T23:59:59Z");
        Instant octoberReceivedAt = Instant.parse("2026-10-01T00:00:01Z");

        when(accountingEventRepository.save(org.mockito.ArgumentMatchers.any(AccountingEvent.class)))
                .thenReturn(savedEvent(septemberReceivedAt))
                .thenReturn(savedEvent(octoberReceivedAt));

        AccountingEventResponse septemberEvent = service.submitEvent(eventMap("september event"));
        AccountingEventResponse octoberEvent = service.submitEvent(eventMap("october event"));

        assertThat(septemberEvent.getEventReference()).isEqualTo("AE-202609-1");
        assertThat(octoberEvent.getEventReference()).isEqualTo("AE-202610-1");
    }

    private AccountingEvent savedEvent(Instant receivedAt) {
        AccountingEvent event = new AccountingEvent();
        event.setEventId(UUID.randomUUID());
        event.setOrganizationId(testOrganizationId);
        event.setEventType("INVOICE_RECEIVED");
        event.setStatus(AccountingEventStatus.RECEIVED);
        event.setTransactionDate(LocalDateTime.now(TEST_CLOCK));
        event.setReceivedAt(receivedAt);
        return event;
    }

    private Map<String, Object> eventMap(String description) {
        Map<String, Object> map = new HashMap<>();
        map.put("organizationId", testOrganizationId);
        map.put("eventType", "INVOICE_RECEIVED");
        map.put("sourceSystem", "MYOB");
        map.put("transactionDate", LocalDateTime.now(TEST_CLOCK));
        map.put("payload", Map.of("description", description));
        return map;
    }
}
