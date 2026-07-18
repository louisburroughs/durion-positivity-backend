package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.DefaultGLMappingProperties;
import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.PostingRuleSet;
import com.positivity.accounting.internal.entity.PostingRuleVersion;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.enums.PostingRuleSetState;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.PostingRuleSetRepository;
import com.positivity.accounting.internal.repository.PostingRuleVersionRepository;
import com.positivity.accounting.internal.service.PostingRuleEvaluatorImpl;
import java.math.BigDecimal;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for E1 proportional split lines in
 * {@link PostingRuleEvaluatorImpl} (issue #945).
 *
 * <p>
 * Verifies the residual-distribution algorithm: raw share = amount x
 * factorPercent / 100, each share rounded HALF_UP to 2 decimal places, and
 * the residual (source minus sum of rounded shares) placed on the line with
 * the largest absolute raw share — first line in rule order on ties. Every
 * split group must sum exactly to its source amount, and pre-E1 rules must
 * behave exactly as before.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostingRuleEvaluator split lines (E1)")
class PostingRuleEvaluatorSplitLineTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String EVENT_TYPE = "billing.invoicePosted";

    private static final UUID RULE_SET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID VERSION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

    private static final UUID ACCOUNT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ACCOUNT_B = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID ACCOUNT_C = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID ACCOUNT_D = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    private static final UUID ACCOUNT_E = UUID.fromString("00000000-0000-0000-0000-0000000000a5");

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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubPublishedRules(String rulesDefinition) {
        PostingRuleSet ruleSet = new PostingRuleSet();
        ruleSet.setPostingRuleSetId(RULE_SET_ID);
        ruleSet.setName("Split rules");
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

    private AccountingEvent createEvent(String amount) {
        AccountingEvent event = new AccountingEvent();
        event.setEventId(UUID.fromString("00000000-0000-0000-0000-0000000000e1"));
        event.setOrganizationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        event.setEventType(EVENT_TYPE);
        event.setTransactionDate(LocalDateTime.of(2026, 1, 15, 10, 30));
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        event.setPayload(payload);
        event.setReceivedAt(Instant.now(TEST_CLOCK));
        event.setSourceSystem("TEST_SYSTEM");
        return event;
    }

    private static String line(UUID account, String side, String extraJson) {
        return "{\"glAccountId\":\"" + account + "\",\"side\":\"" + side + "\",\"amountField\":\"payload.amount\""
                + (extraJson.isEmpty() ? "" : "," + extraJson) + "}";
    }

    private static String splitLine(UUID account, String side, String factor, String group) {
        return line(account, side, "\"factorPercent\":" + factor + ",\"splitGroup\":\"" + group + "\"");
    }

    private static String rules(String... lines) {
        return "{\"conditions\":[{\"condition\":\"eventType == '" + EVENT_TYPE + "'\",\"lines\":["
                + String.join(",", lines) + "]}]}";
    }

    /** Rules with a debit split group (given factors) balanced by one credit line. */
    private static String debitSplitRules(String[] factors, UUID... debitAccounts) {
        String[] lines = new String[factors.length + 1];
        for (int i = 0; i < factors.length; i++) {
            lines[i] = splitLine(debitAccounts[i], "DEBIT", factors[i], "g1");
        }
        lines[factors.length] = line(ACCOUNT_E, "CREDIT", "");
        return rules(lines);
    }

    private List<JournalEntryLine> evaluateExpectingSuccess(String amount) {
        PostingResult result = evaluator.evaluateEvent(createEvent(amount), null);
        assertThat(result.isSuccess()).as("evaluation success").isTrue();
        JournalEntry entry = result.getJournalEntryDraft();
        assertThat(entry).isNotNull();
        assertThat(entry.getTotalDebits()).isEqualByComparingTo(entry.getTotalCredits());
        return entry.getLines();
    }

    private static BigDecimal debit(List<JournalEntryLine> lines, int index) {
        return lines.get(index).getDebitAmount();
    }

    // ── Split distribution ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Residual distribution")
    class ResidualDistribution {

        @Test
        @DisplayName("60/40 over 100.00 splits exactly with no residual")
        void sixtyFortyOverHundred() {
            stubPublishedRules(debitSplitRules(new String[] {"60", "40"}, ACCOUNT_A, ACCOUNT_B));

            List<JournalEntryLine> lines = evaluateExpectingSuccess("100.00");

            assertThat(debit(lines, 0)).isEqualByComparingTo("60.00");
            assertThat(debit(lines, 1)).isEqualByComparingTo("40.00");
            assertThat(debit(lines, 0).add(debit(lines, 1))).isEqualByComparingTo("100.00");
            assertThat(lines.get(2).getCreditAmount()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("33.33/33.33/33.34 over 100.00 needs no residual")
        void threeWayOverHundred() {
            stubPublishedRules(
                    debitSplitRules(new String[] {"33.33", "33.33", "33.34"}, ACCOUNT_A, ACCOUNT_B, ACCOUNT_C));

            List<JournalEntryLine> lines = evaluateExpectingSuccess("100.00");

            assertThat(debit(lines, 0)).isEqualByComparingTo("33.33");
            assertThat(debit(lines, 1)).isEqualByComparingTo("33.33");
            assertThat(debit(lines, 2)).isEqualByComparingTo("33.34");
            assertThat(debit(lines, 0).add(debit(lines, 1)).add(debit(lines, 2)))
                    .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("33.33/33.33/33.34 over 0.01 places whole cent on largest raw share")
        void threeWayOverOneCent() {
            stubPublishedRules(
                    debitSplitRules(new String[] {"33.33", "33.33", "33.34"}, ACCOUNT_A, ACCOUNT_B, ACCOUNT_C));

            // Raw shares 0.003333 / 0.003333 / 0.003334 all round to 0.00;
            // residual +0.01 goes to line 3 (largest raw share).
            List<JournalEntryLine> lines = evaluateExpectingSuccess("0.01");

            assertThat(debit(lines, 0)).isEqualByComparingTo("0.00");
            assertThat(debit(lines, 1)).isEqualByComparingTo("0.00");
            assertThat(debit(lines, 2)).isEqualByComparingTo("0.01");
            assertThat(debit(lines, 0).add(debit(lines, 1)).add(debit(lines, 2)))
                    .isEqualByComparingTo("0.01");
        }

        @Test
        @DisplayName("33.33/33.33/33.34 over 99.99 subtracts negative residual from largest raw share")
        void threeWayOverNinetyNineNinetyNine() {
            stubPublishedRules(
                    debitSplitRules(new String[] {"33.33", "33.33", "33.34"}, ACCOUNT_A, ACCOUNT_B, ACCOUNT_C));

            // Raw shares 33.326667 / 33.326667 / 33.336666 round to
            // 33.33 / 33.33 / 33.34 (sum 100.00); residual -0.01 lands on
            // line 3 (largest raw share): 33.34 - 0.01 = 33.33.
            List<JournalEntryLine> lines = evaluateExpectingSuccess("99.99");

            assertThat(debit(lines, 0)).isEqualByComparingTo("33.33");
            assertThat(debit(lines, 1)).isEqualByComparingTo("33.33");
            assertThat(debit(lines, 2)).isEqualByComparingTo("33.33");
            assertThat(debit(lines, 0).add(debit(lines, 1)).add(debit(lines, 2)))
                    .isEqualByComparingTo("99.99");
        }

        @Test
        @DisplayName("tie on raw share is broken by rule order: first line takes the residual")
        void tieBreakIsFirstLineInRuleOrder() {
            stubPublishedRules(debitSplitRules(new String[] {"50", "50"}, ACCOUNT_A, ACCOUNT_B));

            // Raw shares 0.005 / 0.005 both round HALF_UP to 0.01 (sum 0.02);
            // residual -0.01 must hit the FIRST line deterministically.
            List<JournalEntryLine> lines = evaluateExpectingSuccess("0.01");

            assertThat(debit(lines, 0)).isEqualByComparingTo("0.00");
            assertThat(debit(lines, 1)).isEqualByComparingTo("0.01");
            assertThat(debit(lines, 0).add(debit(lines, 1))).isEqualByComparingTo("0.01");
        }
    }

    // ── Composition with non-split lines and multiple groups ────────────────

    @Nested
    @DisplayName("Composition")
    class Composition {

        @Test
        @DisplayName("split group coexists with untouched non-split lines in the same condition")
        void splitGroupAmongNonSplitLines() {
            stubPublishedRules(rules(
                    line(ACCOUNT_D, "DEBIT", ""),
                    splitLine(ACCOUNT_A, "CREDIT", "60", "rev"),
                    splitLine(ACCOUNT_B, "CREDIT", "40", "rev")));

            List<JournalEntryLine> lines = evaluateExpectingSuccess("250.10");

            assertThat(lines.get(0).getDebitAmount()).isEqualByComparingTo("250.10");
            assertThat(lines.get(1).getCreditAmount()).isEqualByComparingTo("150.06");
            assertThat(lines.get(2).getCreditAmount()).isEqualByComparingTo("100.04");
        }

        @Test
        @DisplayName("two independent split groups in one condition each sum to the source amount")
        void twoIndependentSplitGroups() {
            stubPublishedRules(rules(
                    splitLine(ACCOUNT_A, "DEBIT", "60", "dr"),
                    splitLine(ACCOUNT_B, "DEBIT", "40", "dr"),
                    splitLine(ACCOUNT_C, "CREDIT", "70", "cr"),
                    splitLine(ACCOUNT_D, "CREDIT", "30", "cr")));

            List<JournalEntryLine> lines = evaluateExpectingSuccess("33.35");

            // dr group: raws 20.01 / 13.34 — exact, no residual
            assertThat(lines.get(0).getDebitAmount()).isEqualByComparingTo("20.01");
            assertThat(lines.get(1).getDebitAmount()).isEqualByComparingTo("13.34");
            // cr group: raws 23.345 / 10.005 round to 23.35 / 10.01 (sum
            // 33.36); residual -0.01 lands on the largest raw share (line 3)
            assertThat(lines.get(2).getCreditAmount()).isEqualByComparingTo("23.34");
            assertThat(lines.get(3).getCreditAmount()).isEqualByComparingTo("10.01");
            assertThat(lines.get(2).getCreditAmount().add(lines.get(3).getCreditAmount()))
                    .isEqualByComparingTo("33.35");
        }
    }

    // ── Backward compatibility and defensive failures ────────────────────────

    @Nested
    @DisplayName("Backward compatibility and defensive failures")
    class BackwardCompatibilityAndFailures {

        @Test
        @DisplayName("pre-E1 rules without split fields behave exactly as before (no rounding applied)")
        void plainRulesUnchanged() {
            stubPublishedRules(rules(line(ACCOUNT_A, "DEBIT", ""), line(ACCOUNT_B, "CREDIT", "")));

            // 3-decimal amount passes through untouched — the evaluator only
            // rounds split shares, never plain lines.
            List<JournalEntryLine> lines = evaluateExpectingSuccess("100.005");

            assertThat(lines).hasSize(2);
            assertThat(lines.get(0).getDebitAmount()).isEqualByComparingTo("100.005");
            assertThat(lines.get(1).getCreditAmount()).isEqualByComparingTo("100.005");
        }

        @Test
        @DisplayName("mixed DEBIT/CREDIT split group fails evaluation explicitly")
        void mixedSideGroupFails() {
            stubPublishedRules(rules(
                    splitLine(ACCOUNT_A, "DEBIT", "60", "g1"),
                    splitLine(ACCOUNT_B, "CREDIT", "40", "g1"),
                    line(ACCOUNT_E, "CREDIT", "")));

            PostingResult result = evaluator.evaluateEvent(createEvent("100.00"), null);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.INTERNAL_ERROR);
            assertThat(result.getFailureDetails()).contains("mixes DEBIT and CREDIT");
        }

        @Test
        @DisplayName("split group whose factors do not sum to 100 fails instead of silently rebalancing")
        void factorSumMismatchFails() {
            stubPublishedRules(debitSplitRules(new String[] {"60", "30"}, ACCOUNT_A, ACCOUNT_B));

            PostingResult result = evaluator.evaluateEvent(createEvent("100.00"), null);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.INTERNAL_ERROR);
            assertThat(result.getFailureDetails()).contains("sum to 90");
        }

        @Test
        @DisplayName("factorPercent without splitGroup fails evaluation explicitly")
        void orphanFactorPercentFails() {
            stubPublishedRules(rules(line(ACCOUNT_A, "DEBIT", "\"factorPercent\":60"), line(ACCOUNT_E, "CREDIT", "")));

            PostingResult result = evaluator.evaluateEvent(createEvent("100.00"), null);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.INTERNAL_ERROR);
            assertThat(result.getFailureDetails()).contains("factorPercent without splitGroup");
        }
    }
}
