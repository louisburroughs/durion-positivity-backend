package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.DefaultGLMappingProperties;
import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.PostingRuleSet;
import com.positivity.accounting.internal.entity.PostingRuleVersion;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.enums.PostingRuleSetState;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.PostingRuleSetRepository;
import com.positivity.accounting.internal.repository.PostingRuleVersionRepository;
import com.positivity.accounting.internal.service.PostingRuleEvaluatorImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for E2 predicate matching inside
 * {@link PostingRuleEvaluatorImpl#evaluateEvent} (issue #946): payload
 * predicates route events to different account lines under first-match-wins
 * semantics, and published pre-E2 rules with unparseable condition text stay
 * a defensive non-match (WARN, skip) instead of an error.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostingRuleEvaluator condition predicates (E2)")
class PostingRuleEvaluatorPredicateTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String EVENT_TYPE = "billing.paymentReceived";

    private static final UUID RULE_SET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID VERSION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

    private static final UUID CASH_DEBIT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID CARD_DEBIT = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID FALLBACK_DEBIT = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID CREDIT = UUID.fromString("00000000-0000-0000-0000-0000000000c9");

    @Mock
    private PostingRuleVersionRepository versionRepository;

    @Mock
    private PostingRuleSetRepository ruleSetRepository;

    @Mock
    private GLMappingResolver glMappingResolver;

    @Mock
    private DefaultGLMappingRepository defaultGLMappingRepository;

    private PostingRuleEvaluatorImpl evaluator;

    @BeforeEach
    void setUp() {
        DefaultGLMappingProperties properties = new DefaultGLMappingProperties();
        properties.setEnabled(false);

        evaluator = new PostingRuleEvaluatorImpl(
                TEST_CLOCK,
                versionRepository,
                ruleSetRepository,
                glMappingResolver,
                defaultGLMappingRepository,
                properties,
                new ObjectMapper());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void stubPublishedRules(String rulesDefinition) {
        PostingRuleSet ruleSet = new PostingRuleSet();
        ruleSet.setPostingRuleSetId(RULE_SET_ID);
        ruleSet.setName("Predicate rules");
        ruleSet.setEventType(EVENT_TYPE);

        PostingRuleVersion version = new PostingRuleVersion();
        version.setVersionId(VERSION_ID);
        version.setVersionNumber(1);
        version.setState(PostingRuleSetState.PUBLISHED);
        version.setRulesDefinition(rulesDefinition);
        version.setPostingRuleSet(ruleSet);

        when(ruleSetRepository.findByEventType(EVENT_TYPE)).thenReturn(List.of(ruleSet));
        when(versionRepository.findByPostingRuleSet_PostingRuleSetIdAndState(
                        RULE_SET_ID, PostingRuleSetState.PUBLISHED))
                .thenReturn(List.of(version));
    }

    private AccountingEvent createEvent(String amount, String paymentMethod) {
        AccountingEvent event = new AccountingEvent();
        event.setEventId(UUID.fromString("00000000-0000-0000-0000-0000000000e1"));
        event.setOrganizationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        event.setEventType(EVENT_TYPE);
        event.setTransactionDate(LocalDateTime.of(2026, 1, 15, 10, 30));
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        if (paymentMethod != null) {
            payload.put("paymentMethod", paymentMethod);
        }
        event.setPayload(payload);
        event.setReceivedAt(Instant.now(TEST_CLOCK));
        event.setSourceSystem("E2_TEST");
        return event;
    }

    private static String balancedLines(UUID debitAccount) {
        return "[{\"glAccountId\":\"" + debitAccount + "\",\"side\":\"DEBIT\",\"amountField\":\"payload.amount\"},"
                + "{\"glAccountId\":\"" + CREDIT + "\",\"side\":\"CREDIT\",\"amountField\":\"payload.amount\"}]";
    }

    private static String conditionBlock(String conditionJsonValue, UUID debitAccount) {
        return "{\"condition\":" + conditionJsonValue + ",\"lines\":" + balancedLines(debitAccount) + "}";
    }

    private static String rules(String... conditionBlocks) {
        return "{\"conditions\":[" + String.join(",", conditionBlocks) + "]}";
    }

    private UUID debitAccountOf(PostingResult result) {
        assertThat(result.isSuccess()).as("evaluation success").isTrue();
        JournalEntry entry = result.getJournalEntryDraft();
        assertThat(entry).isNotNull();
        return entry.getLines().get(0).getGlAccountId();
    }

    // ── Routing ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("payload.paymentMethod predicates route CASH and CARD events to different accounts")
    void paymentMethodRoutesToDifferentAccounts() {
        stubPublishedRules(rules(
                conditionBlock("\"payload.paymentMethod == 'CASH'\"", CASH_DEBIT),
                conditionBlock("\"payload.paymentMethod == 'CARD'\"", CARD_DEBIT),
                conditionBlock("\"*\"", FALLBACK_DEBIT)));

        assertThat(debitAccountOf(evaluator.evaluateEvent(createEvent("50.00", "CASH"), null)))
                .isEqualTo(CASH_DEBIT);
        assertThat(debitAccountOf(evaluator.evaluateEvent(createEvent("50.00", "CARD"), null)))
                .isEqualTo(CARD_DEBIT);
        assertThat(debitAccountOf(evaluator.evaluateEvent(createEvent("50.00", "CHECK"), null)))
                .isEqualTo(FALLBACK_DEBIT);
        assertThat(debitAccountOf(evaluator.evaluateEvent(createEvent("50.00", null), null)))
                .isEqualTo(FALLBACK_DEBIT);
    }

    @Test
    @DisplayName("conjunction predicate with numeric threshold routes first-match-wins")
    void conjunctionThresholdFirstMatchWins() {
        stubPublishedRules(rules(
                conditionBlock("\"payload.amount >= 100.00 && eventType == '" + EVENT_TYPE + "'\"", CASH_DEBIT),
                conditionBlock("\"*\"", FALLBACK_DEBIT)));

        assertThat(debitAccountOf(evaluator.evaluateEvent(createEvent("150.00", "CASH"), null)))
                .isEqualTo(CASH_DEBIT);
        assertThat(debitAccountOf(evaluator.evaluateEvent(createEvent("100.00", "CASH"), null)))
                .isEqualTo(CASH_DEBIT);
        assertThat(debitAccountOf(evaluator.evaluateEvent(createEvent("99.99", "CASH"), null)))
                .isEqualTo(FALLBACK_DEBIT);
    }

    @Test
    @DisplayName("missing payload path is a non-match: event falls through to the catch-all rule")
    void missingPathFallsThrough() {
        stubPublishedRules(rules(
                conditionBlock("\"payload.missing.deep == 'x'\"", CASH_DEBIT),
                conditionBlock("\"*\"", FALLBACK_DEBIT)));

        assertThat(debitAccountOf(evaluator.evaluateEvent(createEvent("10.00", "CASH"), null)))
                .isEqualTo(FALLBACK_DEBIT);
    }

    // ── Defensive behavior for pre-E2 garbage conditions ─────────────────

    @Test
    @DisplayName("published rule with unparseable condition is skipped (non-match), catch-all still applies")
    void garbageConditionSkippedWithCatchAll() {
        stubPublishedRules(rules(
                conditionBlock("\"if amount then post!!\"", CASH_DEBIT), conditionBlock("\"*\"", FALLBACK_DEBIT)));

        assertThat(debitAccountOf(evaluator.evaluateEvent(createEvent("10.00", "CASH"), null)))
                .isEqualTo(FALLBACK_DEBIT);
    }

    @Test
    @DisplayName("published rule with only an unparseable condition fails as UNMAPPED_EVENT_TYPE, not an error")
    void garbageConditionAloneFailsUnmapped() {
        stubPublishedRules(rules(conditionBlock("\"if amount then post!!\"", CASH_DEBIT)));

        PostingResult result = evaluator.evaluateEvent(createEvent("10.00", "CASH"), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.UNMAPPED_EVENT_TYPE);
    }
}
