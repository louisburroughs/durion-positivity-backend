package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.mcp.internal.dto.ClarificationResponseDTO;
import com.positivity.mcp.internal.dto.IntentV1;
import com.positivity.mcp.internal.entity.NltiIntent;
import com.positivity.mcp.internal.enums.NltiIntentStatus;
import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import com.positivity.mcp.internal.repository.NltiIntentRepository;
import com.positivity.mcp.service.AuditLedgerService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Unit tests for {@link IntentParserServiceImpl} covering NLTI-002 acceptance
 * criteria: QUERY/READY classification, NEEDS_CLARIFICATION for ambiguous
 * prompts,
 * clarification resolution state transitions, HIGH-risk flagging for
 * destructive
 * intents, and metric emission contract.
 *
 * Issue: NLTI-002
 *
 * @see IntentParserServiceImpl
 */
@ExtendWith(MockitoExtension.class)
class IntentParserServiceTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR-0013
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000300");
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-7000-8000-000000000301");
    private static final UUID INTENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000302");

    @Mock
    private NltiIntentRepository intentRepository;

    @Mock
    private AuditLedgerService auditLedgerService;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Clock clock;

    @InjectMocks
    private IntentParserServiceImpl service;

    @BeforeEach
    void setUp() {
        // Stub clock so OffsetDateTime.now(clock) works in all parse() calls
        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-03-12T10:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        // Stub repository for resolve() tests: return an existing NltiIntent by
        // INTENT_ID
        NltiIntent existingIntent = new NltiIntent();
        existingIntent.setId(INTENT_ID);
        existingIntent.setSessionId(SESSION_ID);
        existingIntent.setCorrelationId(CORRELATION_ID);
        existingIntent.setIntentType(NltiIntentType.UNKNOWN);
        existingIntent.setStatus(NltiIntentStatus.NEEDS_CLARIFICATION);
        existingIntent.setRiskLevel(NltiRiskLevel.LOW);
        existingIntent.setSlotsJson("[]");
        existingIntent.setClarificationQuestionsJson("[]");
        lenient().when(intentRepository.findById(INTENT_ID)).thenReturn(Optional.of(existingIntent));
        lenient().when(intentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Stub meterRegistry to prevent NPE from lazy counter initialization
        Counter parseCounter = mock(Counter.class);
        Counter clariCounter = mock(Counter.class);
        lenient().when(meterRegistry.counter("nlt.intent.parse.count")).thenReturn(parseCounter);
        lenient().when(meterRegistry.counter("nlt.intent.clarification.count")).thenReturn(clariCounter);
    }

    // ─── AC1: clear read-only request → QUERY / READY ───────────────────────

    /**
     * A clear, unambiguous read-only prompt must produce intentType=QUERY,
     * status=READY with slot confidences ≥ 0.0.
     *
     * @see <a href=
     *      "https://github.com/louisburroughs/durion-positivity-backend/issues/NLTI-002">NLTI-002
     *      AC1</a>
     */
    @Test
    @DisplayName("parse: clear read-only request returns QUERY/READY intent")
    void parse_clearReadOnlyRequest_returnsQueryReady() {
        // Issue NLTI-002: AC1 — clear read-only → QUERY, READY
        IntentV1 result = service.parse("check tire inventory in Charlotte", SESSION_ID, CORRELATION_ID);

        assertThat(result.intentType()).isEqualTo("QUERY");
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.riskLevel()).isEqualTo("LOW");
        assertThat(result.slots()).isNotNull();
        result.slots().forEach(slot -> assertThat(slot.confidence()).isGreaterThanOrEqualTo(0.0));
    }

    // ─── AC2: ambiguous request → NEEDS_CLARIFICATION ───────────────────────

    /**
     * An ambiguous prompt must produce status=NEEDS_CLARIFICATION with at least
     * one clarification question having ≥ 2 options.
     *
     * @see <a href=
     *      "https://github.com/louisburroughs/durion-positivity-backend/issues/NLTI-002">NLTI-002
     *      AC2</a>
     */
    @Test
    @DisplayName("parse: ambiguous request returns NEEDS_CLARIFICATION with questions")
    void parse_ambiguousRequest_returnsNeedsClarification() {
        // Issue NLTI-002: AC2 — ambiguous → NEEDS_CLARIFICATION, each question has ≥ 2
        // options
        IntentV1 result = service.parse("help me with orders", SESSION_ID, CORRELATION_ID);

        assertThat(result.status()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(result.clarificationQuestions()).isNotEmpty();
        result.clarificationQuestions()
                .forEach(q -> assertThat(q.options()).hasSizeGreaterThanOrEqualTo(2));
    }

    // ─── AC3: clarification resolution → READY ──────────────────────────────

    /**
     * When a valid clarification answer fully resolves all slots the intent must
     * transition to status=READY.
     *
     * @see <a href=
     *      "https://github.com/louisburroughs/durion-positivity-backend/issues/NLTI-002">NLTI-002
     *      AC3</a>
     */
    @Test
    @DisplayName("resolve: valid answer with full slot resolution transitions to READY")
    void resolve_withValidAnswer_transitionsToReady() {
        // Issue NLTI-002: AC3 — resolve with unambiguous answer → READY, all slots
        // non-null
        var response = new ClarificationResponseDTO(INTENT_ID, "inventory", "inventory check");

        IntentV1 result = service.resolve(INTENT_ID, response);

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.slots()).isNotNull();
        result.slots().forEach(slot -> assertThat(slot.value()).isNotNull());
    }

    /**
     * When a clarification answer is still ambiguous the intent must remain in
     * status=PENDING_CLARIFICATION with remaining open questions.
     *
     * @see <a href=
     *      "https://github.com/louisburroughs/durion-positivity-backend/issues/NLTI-002">NLTI-002
     *      AC3</a>
     */
    @Test
    @DisplayName("resolve: still-ambiguous answer remains PENDING_CLARIFICATION")
    void resolve_withStillAmbiguousAnswer_remainsPendingClarification() {
        // Issue NLTI-002: AC3 — still ambiguous after resolve → PENDING_CLARIFICATION
        var response = new ClarificationResponseDTO(INTENT_ID, null, "something");

        IntentV1 result = service.resolve(INTENT_ID, response);

        assertThat(result.status()).isEqualTo("PENDING_CLARIFICATION");
        assertThat(result.clarificationQuestions()).isNotEmpty();
    }

    // ─── AC4: destructive / bulk intent → HIGH risk ──────────────────────────

    /**
     * A destructive or bulk-delete prompt must produce riskLevel=HIGH and
     * intentType=ACTION.
     *
     * @see <a href=
     *      "https://github.com/louisburroughs/durion-positivity-backend/issues/NLTI-002">NLTI-002
     *      AC4</a>
     */
    @Test
    @DisplayName("parse: destructive intent flagged with HIGH risk level")
    void parse_destructiveIntent_flagsHighRisk() {
        // Issue NLTI-002: AC4 — destructive/bulk → ACTION, HIGH risk
        IntentV1 result = service.parse("delete all workorders from last year", SESSION_ID, CORRELATION_ID);

        assertThat(result.intentType()).isEqualTo("ACTION");
        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.status()).isEqualTo("NEEDS_CLARIFICATION");
    }

    // ─── AC5: metric emission ────────────────────────────────────────────────

    /**
     * Each parse() call must increment the {@code nlt.intent.parse.count} counter.
     *
     * @see <a href=
     *      "https://github.com/louisburroughs/durion-positivity-backend/issues/NLTI-002">NLTI-002
     *      AC5</a>
     */
    @Test
    @DisplayName("parse: emits nlt.intent.parse.count metric on every call")
    void parse_emitsIntentParseCountMetric() {
        // Issue NLTI-002: AC5 — counter "nlt.intent.parse.count" incremented per
        // parse()
        Counter counter = mock(Counter.class);
        when(meterRegistry.counter("nlt.intent.parse.count")).thenReturn(counter);

        service.parse("list open invoices", SESSION_ID, CORRELATION_ID);

        verify(counter, times(1)).increment();
    }

    // ─── Counter lazy-init false-branch: counter already set on second call ─

    @Test
    @DisplayName("parse: reuses cached parse counter on subsequent calls (null-check false branch)")
    void parse_calledTwice_reusesCachedParseCounter() {
        Counter counter = mock(Counter.class);
        when(meterRegistry.counter("nlt.intent.parse.count")).thenReturn(counter);

        service.parse("list items", SESSION_ID, CORRELATION_ID);
        service.parse("check stock", SESSION_ID, CORRELATION_ID);

        // Counter incremented twice; meterRegistry.counter() should only be called once
        // (null-check
        // skipped on second call because parseCounter field is already populated)
        verify(meterRegistry, times(1)).counter("nlt.intent.parse.count");
        verify(counter, times(2)).increment();
    }

    @Test
    @DisplayName("parse: reuses cached clarification counter on subsequent ambiguous calls (null-check false branch)")
    void parse_calledTwiceAmbiguous_reusesClarificationCounter() {
        Counter clariCounter = mock(Counter.class);
        when(meterRegistry.counter("nlt.intent.clarification.count")).thenReturn(clariCounter);

        // Both calls are ambiguous → NEEDS_CLARIFICATION → clarification counter
        // incremented twice
        service.parse("help me with something", SESSION_ID, CORRELATION_ID);
        service.parse("help me do stuff", SESSION_ID, CORRELATION_ID);

        verify(meterRegistry, times(1)).counter("nlt.intent.clarification.count");
        verify(clariCounter, times(2)).increment();
    }

    // ─── parse: repository.save() returning null ────────────────────────────

    @Test
    @DisplayName("parse: falls back to local intent copy when repository.save() returns null")
    void parse_withSaveReturningNull_returnsFallbackIntent() {
        when(intentRepository.save(any())).thenReturn(null);

        IntentV1 result = service.parse("check tire inventory", SESSION_ID, CORRELATION_ID);

        assertThat(result).isNotNull();
        assertThat(result.intentType()).isEqualTo("QUERY");
        assertThat(result.status()).isEqualTo("READY");
    }

    // ─── toIntentV1: null intentType / status / riskLevel ───────────────────

    @Test
    @DisplayName("toIntentV1: returns null string fields when intent has null type/status/risk")
    void toIntentV1_withNullFields_returnsNullStringFields() {
        // Configure save to return a minimal intent without type, status, or riskLevel
        // set
        NltiIntent sparseIntent = new NltiIntent();
        sparseIntent.setId(INTENT_ID);
        sparseIntent.setSessionId(SESSION_ID);
        sparseIntent.setCorrelationId(CORRELATION_ID);
        // intentType, status, riskLevel deliberately left null
        sparseIntent.setSlotsJson("[]");
        sparseIntent.setClarificationQuestionsJson("[]");
        when(intentRepository.save(any())).thenReturn(sparseIntent);

        IntentV1 result = service.parse("check inventory levels", SESSION_ID, CORRELATION_ID);

        assertThat(result.intentType()).isNull();
        assertThat(result.status()).isNull();
        assertThat(result.riskLevel()).isNull();
    }

    // ─── resolve: answer == null branch ─────────────────────────────────────

    @Test
    @DisplayName("resolve: null userAnswer triggers PENDING_CLARIFICATION (answer != null false-branch)")
    void resolve_withNullUserAnswer_remainsPendingClarification() {
        // selectedOption=non-null, userAnswer=null → first condition (answer != null)
        // fails immediately
        var response = new ClarificationResponseDTO(INTENT_ID, "Check inventory", null);

        IntentV1 result = service.resolve(INTENT_ID, response);

        assertThat(result.status()).isEqualTo("PENDING_CLARIFICATION");
        assertThat(result.clarificationQuestions()).isNotEmpty();
    }

    // ─── resolve: selectedOption blank branch ────────────────────────────────

    @Test
    @DisplayName("resolve: blank selectedOption triggers PENDING_CLARIFICATION (!selectedOption.isBlank() false-branch)")
    void resolve_withBlankSelectedOption_remainsPendingClarification() {
        // answer=non-blank, selectedOption=blank → fourth condition
        // (!selectedOption.isBlank()) false
        var response = new ClarificationResponseDTO(INTENT_ID, "  ", "inventory check");

        IntentV1 result = service.resolve(INTENT_ID, response);

        assertThat(result.status()).isEqualTo("PENDING_CLARIFICATION");
    }

    // ─── resolve: intent not found → NoSuchElementException ─────────────────

    @Test
    @DisplayName("resolve: unknown intentId throws NoSuchElementException")
    void resolve_withNonExistentIntentId_throwsNoSuchElementException() {
        UUID unknownId = UUID.fromString("00000000-0000-7000-8000-000000000399");
        when(intentRepository.findById(unknownId)).thenReturn(Optional.empty());
        var response = new ClarificationResponseDTO(unknownId, "Check inventory", "answer");

        assertThrows(NoSuchElementException.class, () -> service.resolve(unknownId, response));
    }

    // ─── extractSubject: query indicator at end with no following word ───────

    @Test
    @DisplayName("parse: query indicator with no trailing word extracts subject as 'unknown'")
    void parse_withQueryIndicatorAloneAtEnd_extractsUnknownSubject() {
        // "price" is in QUERY_INDICATORS → classifyType returns QUERY → buildSlots
        // calls extractSubject
        // extractSubject("price"): "price" found, rest = "", words[0] = "" (blank) →
        // continue
        // No other indicator matches in "price" (no "check", "show", etc.) → return
        // "unknown"
        IntentV1 result = service.parse("price", SESSION_ID, CORRELATION_ID);

        assertThat(result.intentType()).isEqualTo("QUERY");
        assertThat(result.slots()).isNotEmpty();
        assertThat(result.slots().get(0).value()).isEqualTo("unknown");
    }

    // ─── fromJson: corrupted JSON returned from save triggers catch block ────

    @Test
    @DisplayName("fromJson: corrupted slots/clarification JSON from persisted intent returns empty lists")
    void fromJson_withCorruptedJsonInPersistedIntent_returnsEmptyLists() {
        // Configure save (in resolve path) to return an intent with non-parseable JSON
        // arrays
        NltiIntent corruptedReturn = new NltiIntent();
        corruptedReturn.setId(INTENT_ID);
        corruptedReturn.setSessionId(SESSION_ID);
        corruptedReturn.setCorrelationId(CORRELATION_ID);
        corruptedReturn.setIntentType(NltiIntentType.UNKNOWN);
        corruptedReturn.setStatus(NltiIntentStatus.READY);
        corruptedReturn.setRiskLevel(NltiRiskLevel.LOW);
        corruptedReturn.setSlotsJson("{not-a-json-array}");
        corruptedReturn.setClarificationQuestionsJson("{also-not-valid}");
        when(intentRepository.save(any())).thenReturn(corruptedReturn);

        var response = new ClarificationResponseDTO(INTENT_ID, "inventory", "inventory check");
        IntentV1 result = service.resolve(INTENT_ID, response);

        // fromJson falls back to empty list on JsonProcessingException
        assertThat(result.slots()).isEmpty();
        assertThat(result.clarificationQuestions()).isEmpty();
    }

    // ─── Constructor: 5-param with null ObjectMapper (null-safe branch) ──────

    @Test
    @DisplayName("5-param constructor: null ObjectMapper argument is replaced by default ObjectMapper")
    void constructor_withNullObjectMapper_createsWorkingInstance() {
        // Passing null for objectMapper → constructor null-safe branch uses new
        // ObjectMapper()
        var svc = new IntentParserServiceImpl(
                intentRepository, auditLedgerService, meterRegistry, clock, null);

        IntentV1 result = svc.parse("check tire status", SESSION_ID, CORRELATION_ID);

        assertThat(result).isNotNull();
        assertThat(result.intentType()).isEqualTo("QUERY");
    }

    // ─── Constructor: 4-param delegate ───────────────────────────────────────

    @Test
    @DisplayName("4-param constructor delegates to 5-param constructor with default ObjectMapper")
    void constructor_fourParam_createsWorkingInstance() {
        var svc = new IntentParserServiceImpl(
                intentRepository, auditLedgerService, meterRegistry, clock);

        IntentV1 result = svc.parse("list open workorders", SESSION_ID, CORRELATION_ID);

        assertThat(result).isNotNull();
        assertThat(result.intentType()).isEqualTo("QUERY");
    }

    // ─── classifyRisk: bulk prefix → HIGH risk ───────────────────────────────

    @Test
    @DisplayName("parse: 'bulk ' prefix is classified as HIGH risk ACTION")
    void parse_withBulkPrefix_returnsHighRiskAction() {
        IntentV1 result = service.parse("bulk update prices", SESSION_ID, CORRELATION_ID);

        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.intentType()).isEqualTo("ACTION");
    }

    // ─── resolve: answer blank (answer.isBlank() true, second && short-circuits)

    @Test
    @DisplayName("resolve: blank userAnswer triggers PENDING_CLARIFICATION (!answer.isBlank() false-branch)")
    void resolve_withBlankUserAnswer_remainsPendingClarification() {
        // answer is non-null but blank → second condition (!answer.isBlank()) fails
        var response = new ClarificationResponseDTO(INTENT_ID, "inventory", "   ");

        IntentV1 result = service.resolve(INTENT_ID, response);

        assertThat(result.status()).isEqualTo("PENDING_CLARIFICATION");
    }
}
