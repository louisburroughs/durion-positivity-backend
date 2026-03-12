package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.dto.IntentV1;
import com.positivity.mcp.internal.repository.NltiIntentRepository;
import com.positivity.mcp.service.AuditLedgerService;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Benchmark tests verifying that {@link IntentParserServiceImpl} correctly
 * classifies a representative set of POS-domain utterances across three
 * categories: QUERY/READY (read-only), NEEDS_CLARIFICATION (ambiguous), and
 * HIGH-risk ACTION (destructive/bulk).
 *
 * Issue: NLTI-002
 */
@ExtendWith(MockitoExtension.class)
class IntentClassifierBenchmarkTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR-0013
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000310");
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-7000-8000-000000000311");

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
        // Stub clock to prevent NullPointerException from OffsetDateTime.now(clock)
        org.mockito.Mockito.lenient().when(clock.instant()).thenReturn(java.time.Instant.parse("2026-03-12T10:00:00Z"));
        org.mockito.Mockito.lenient().when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
        // Stub repository.save to return the saved entity
        org.mockito.Mockito.lenient().when(intentRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        // Stub meterRegistry to prevent NPE from lazy counter initialization
        io.micrometer.core.instrument.Counter parseCounter = org.mockito.Mockito.mock(io.micrometer.core.instrument.Counter.class);
        io.micrometer.core.instrument.Counter clarificationCounter = org.mockito.Mockito.mock(io.micrometer.core.instrument.Counter.class);
        org.mockito.Mockito.lenient().when(meterRegistry.counter("nlt.intent.parse.count")).thenReturn(parseCounter);
        org.mockito.Mockito.lenient().when(meterRegistry.counter("nlt.intent.clarification.count")).thenReturn(clarificationCounter);
    }

    // ─── Benchmark data sources ──────────────────────────────────────────────

    /**
     * Ten clear, unambiguous read-only POS utterances that must resolve to
     * intentType=QUERY, status=READY, riskLevel=LOW.
     */
    static Stream<Arguments> queryReadyUtterances() {
        return Stream.of(
                Arguments.of("check tire stock in Charlotte", "QUERY", "READY", "LOW"),
                Arguments.of("how many brake pads do we have", "QUERY", "READY", "LOW"),
                Arguments.of("show me open workorders", "QUERY", "READY", "LOW"),
                Arguments.of("list invoices for customer 123", "QUERY", "READY", "LOW"),
                Arguments.of("what is the price of Michelin 225/65R17", "QUERY", "READY", "LOW"),
                Arguments.of("find customer John Smith", "QUERY", "READY", "LOW"),
                Arguments.of("show me the accounting summary for March", "QUERY", "READY", "LOW"),
                Arguments.of("get vehicle fitment for 2020 Ford F-150", "QUERY", "READY", "LOW"),
                Arguments.of("list all locations", "QUERY", "READY", "LOW"),
                Arguments.of("what workorders are pending approval", "QUERY", "READY", "LOW"));
    }

    /**
     * Five ambiguous POS utterances that must resolve to
     * intentType=UNKNOWN, status=NEEDS_CLARIFICATION, riskLevel=LOW.
     */
    static Stream<Arguments> needsClarificationUtterances() {
        return Stream.of(
                Arguments.of("help me with orders", "UNKNOWN", "NEEDS_CLARIFICATION", "LOW"),
                Arguments.of("I need to do something with tires", "UNKNOWN", "NEEDS_CLARIFICATION", "LOW"),
                Arguments.of("can you process the request", "UNKNOWN", "NEEDS_CLARIFICATION", "LOW"),
                Arguments.of("update the records", "UNKNOWN", "NEEDS_CLARIFICATION", "LOW"),
                Arguments.of("fix it", "UNKNOWN", "NEEDS_CLARIFICATION", "LOW"));
    }

    /**
     * Three destructive or bulk POS utterances that must be flagged as
     * intentType=ACTION, status=NEEDS_CLARIFICATION, riskLevel=HIGH.
     */
    static Stream<Arguments> highRiskUtterances() {
        return Stream.of(
                Arguments.of("delete all workorders from last year", "ACTION", "NEEDS_CLARIFICATION", "HIGH"),
                Arguments.of("bulk close all open invoices", "ACTION", "NEEDS_CLARIFICATION", "HIGH"),
                Arguments.of("remove all customer records", "ACTION", "NEEDS_CLARIFICATION", "HIGH"));
    }

    // ─── Benchmark tests ─────────────────────────────────────────────────────

    /**
     * Verifies that clear, read-only POS utterances are classified as
     * QUERY / READY / LOW risk (AC1 benchmark — 10 utterances).
     *
     * @param utterance      source natural-language text
     * @param expectedType   expected intentType ("QUERY")
     * @param expectedStatus expected status ("READY")
     * @param expectedRisk   expected riskLevel ("LOW")
     */
    @ParameterizedTest(name = "[{index}] ''{0}'' → {1}/{2}/{3}")
    @MethodSource("queryReadyUtterances")
    @DisplayName("Benchmark: QUERY/READY utterances classify correctly")
    void benchmark_queryReady_shouldClassifyCorrectly(
            String utterance, String expectedType, String expectedStatus, String expectedRisk) {
        // Issue NLTI-002: AC1 benchmark — 10 clear read-only POS utterances → QUERY/READY/LOW
        IntentV1 result = service.parse(utterance, SESSION_ID, CORRELATION_ID);

        assertThat(result.intentType()).isEqualTo(expectedType);
        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.riskLevel()).isEqualTo(expectedRisk);
        // QUERY/READY results must include slots with valid confidence scores
        assertThat(result.slots()).isNotNull();
        result.slots().forEach(slot -> assertThat(slot.confidence()).isGreaterThanOrEqualTo(0.0));
    }

    /**
     * Verifies that ambiguous POS utterances are classified as
     * UNKNOWN / NEEDS_CLARIFICATION / LOW risk with ≥ 2 options per question
     * (AC2 benchmark — 5 utterances).
     *
     * @param utterance      source natural-language text
     * @param expectedType   expected intentType ("UNKNOWN")
     * @param expectedStatus expected status ("NEEDS_CLARIFICATION")
     * @param expectedRisk   expected riskLevel ("LOW")
     */
    @ParameterizedTest(name = "[{index}] ''{0}'' → {1}/{2}/{3}")
    @MethodSource("needsClarificationUtterances")
    @DisplayName("Benchmark: NEEDS_CLARIFICATION utterances classify correctly")
    void benchmark_needsClarification_shouldClassifyCorrectly(
            String utterance, String expectedType, String expectedStatus, String expectedRisk) {
        // Issue NLTI-002: AC2 benchmark — 5 ambiguous POS utterances → UNKNOWN/NEEDS_CLARIFICATION/LOW
        IntentV1 result = service.parse(utterance, SESSION_ID, CORRELATION_ID);

        assertThat(result.intentType()).isEqualTo(expectedType);
        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.riskLevel()).isEqualTo(expectedRisk);
        assertThat(result.clarificationQuestions()).hasSizeGreaterThanOrEqualTo(1);
        result.clarificationQuestions()
              .forEach(q -> assertThat(q.options()).hasSizeGreaterThanOrEqualTo(2));
    }

    /**
     * Verifies that destructive or bulk POS utterances are flagged as
     * ACTION / NEEDS_CLARIFICATION / HIGH risk (AC2 benchmark — 3 utterances).
     *
     * @param utterance      source natural-language text
     * @param expectedType   expected intentType ("ACTION")
     * @param expectedStatus expected status ("NEEDS_CLARIFICATION")
     * @param expectedRisk   expected riskLevel ("HIGH")
     */
    @ParameterizedTest(name = "[{index}] ''{0}'' → {1}/{2}/{3}")
    @MethodSource("highRiskUtterances")
    @DisplayName("Benchmark: HIGH-risk destructive utterances classified correctly")
    void benchmark_highRisk_shouldClassifyCorrectly(
            String utterance, String expectedType, String expectedStatus, String expectedRisk) {
        // Issue NLTI-002: AC2 benchmark — 3 destructive/bulk POS utterances → ACTION/NEEDS_CLARIFICATION/HIGH
        IntentV1 result = service.parse(utterance, SESSION_ID, CORRELATION_ID);

        assertThat(result.intentType()).isEqualTo(expectedType);
        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.riskLevel()).isEqualTo(expectedRisk);
    }
}
