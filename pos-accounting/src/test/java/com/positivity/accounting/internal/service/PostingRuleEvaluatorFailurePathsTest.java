package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.DefaultGLMappingProperties;
import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.PostingRuleSet;
import com.positivity.accounting.internal.entity.PostingRuleVersion;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.enums.PostingRuleSetState;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.PostingRuleSetRepository;
import com.positivity.accounting.internal.repository.PostingRuleVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * What {@link PostingRuleEvaluatorImpl} does when the published rules are wrong.
 *
 * <h2>Why this test exists</h2>
 *
 * Four of this class's methods carry {@code java:S3776} complexity findings, and the branches those
 * methods were missing were almost entirely the failure arms: the unbalanced-journal rejection, the
 * split-group invariant checks, and the unresolvable-GL-account bail-out had no coverage at all.
 * Those arms are the ones that matter most here. This is double-entry bookkeeping — a rule set that
 * produces an unbalanced entry, or a split group that distorts amounts, must fail loudly rather
 * than post something that later has to be unpicked from a closed period. These tests pin that
 * behaviour before the four methods are split into smaller ones.
 *
 * <h2>One guard deliberately left uncovered</h2>
 *
 * {@code evaluateEvent}'s balance check on the default-GL-mapping path cannot fail:
 * {@code generateJournalEntryFromDefault} builds exactly one debit line and one credit line from
 * the same resolved amount, so the entry is balanced by construction. The guard is kept as defence
 * against a future change to that builder, and left uncovered rather than propped up with a
 * fixture that misrepresents what the builder can produce. The equivalent guard on the published-
 * rules path <em>is</em> reachable, and is covered below.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostingRuleEvaluator — rejecting bad rules")
class PostingRuleEvaluatorFailurePathsTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String EVENT_TYPE = "billing.invoicePosted";

    private static final UUID RULE_SET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID VERSION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
    private static final UUID ACCOUNT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ACCOUNT_B = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID CATEGORY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

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

    @Nested
    @DisplayName("balance")
    class Balance {

        @Test
        @DisplayName("a rule set whose lines do not balance is rejected, not posted")
        void unbalancedRulesAreRejected() {
            // One debit of 100.00 and nothing on the credit side.
            stubPublishedRules(rules(line(ACCOUNT_A, "DEBIT", "payload.amount", "")));

            PostingResult result = evaluator.evaluateEvent(event("100.00"), null);

            // The whole point of the check: an entry that does not balance never becomes a draft,
            // because unpicking it after it has posted into a closed period is far more expensive.
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.UNBALANCED_JOURNAL);
            assertThat(result.getEvaluationDetails()).containsEntry("failureStep", "balanceValidation");
            assertThat(result.getEvaluationDetails()).containsKeys("debitTotal", "creditTotal");
        }

        @Test
        @DisplayName("a difference inside the rounding tolerance still counts as balanced")
        void differenceWithinToleranceIsBalanced() {
            // 0.00005 apart -- below the 0.0001 tolerance, which exists so that a legitimate
            // fractional-cent rounding difference does not block an otherwise correct entry.
            stubPublishedRules(rules(
                    line(ACCOUNT_A, "DEBIT", "payload.amount", ""), line(ACCOUNT_B, "CREDIT", "payload.other", "")));

            PostingResult result = evaluator.evaluateEvent(event("100.00005", "100.00"), null);

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("a difference outside the rounding tolerance does not")
        void differenceOutsideToleranceIsUnbalanced() {
            stubPublishedRules(rules(
                    line(ACCOUNT_A, "DEBIT", "payload.amount", ""), line(ACCOUNT_B, "CREDIT", "payload.other", "")));

            PostingResult result = evaluator.evaluateEvent(event("100.0002", "100.00"), null);

            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.UNBALANCED_JOURNAL);
        }
    }

    @Nested
    @DisplayName("split-group invariants in published rules")
    class SplitGroupInvariants {

        @Test
        @DisplayName("a split-group line with no factorPercent fails the evaluation")
        void missingFactorPercentFails() {
            stubPublishedRules(rules(
                    line(ACCOUNT_A, "DEBIT", "payload.amount", "\"splitGroup\":\"g1\",\"factorPercent\":60"),
                    // Same group, no factor: there is no defensible share to give this line.
                    line(ACCOUNT_B, "DEBIT", "payload.amount", "\"splitGroup\":\"g1\"")));

            assertThat(evaluator.evaluateEvent(event("100.00"), null).getFailureReason())
                    .isEqualTo(PostingFailureReason.INTERNAL_ERROR);
        }

        @Test
        @DisplayName("a split group drawing on two different amount fields fails the evaluation")
        void mixedAmountFieldsFail() {
            stubPublishedRules(rules(
                    line(ACCOUNT_A, "DEBIT", "payload.amount", "\"splitGroup\":\"g1\",\"factorPercent\":60"),
                    line(ACCOUNT_B, "DEBIT", "payload.other", "\"splitGroup\":\"g1\",\"factorPercent\":40")));

            // A group is a proportional division of ONE amount. Two source fields means the shares
            // no longer sum to anything in particular, which is how a split silently unbalances.
            assertThat(evaluator.evaluateEvent(event("100.00"), null).getFailureReason())
                    .isEqualTo(PostingFailureReason.INTERNAL_ERROR);
        }

        @Test
        @DisplayName("a split group with a blank amount field fails the evaluation")
        void blankAmountFieldFails() {
            stubPublishedRules(rules(
                    line(ACCOUNT_A, "DEBIT", "", "\"splitGroup\":\"g1\",\"factorPercent\":60"),
                    line(ACCOUNT_B, "DEBIT", "", "\"splitGroup\":\"g1\",\"factorPercent\":40")));

            assertThat(evaluator.evaluateEvent(event("100.00"), null).getFailureReason())
                    .isEqualTo(PostingFailureReason.INTERNAL_ERROR);
        }

        @Test
        @DisplayName("factors that do not sum to 100 fail the evaluation")
        void factorsNotSummingToHundredFail() {
            stubPublishedRules(rules(
                    line(ACCOUNT_A, "DEBIT", "payload.amount", "\"splitGroup\":\"g1\",\"factorPercent\":60"),
                    line(ACCOUNT_B, "DEBIT", "payload.amount", "\"splitGroup\":\"g1\",\"factorPercent\":30")));

            assertThat(evaluator.evaluateEvent(event("100.00"), null).getFailureReason())
                    .isEqualTo(PostingFailureReason.INTERNAL_ERROR);
        }

        @Test
        @DisplayName("an explicitly blank splitGroup fails rather than being read as no group")
        void blankSplitGroupFails() {
            stubPublishedRules(rules(line(ACCOUNT_A, "DEBIT", "payload.amount", "\"splitGroup\":\"  \"")));

            // Distinct from omitting the key. A blank group name is a rule authoring mistake, and
            // treating it as "not in a group" would quietly post the full amount to one line.
            assertThat(evaluator.evaluateEvent(event("100.00"), null).getFailureReason())
                    .isEqualTo(PostingFailureReason.INTERNAL_ERROR);
        }
    }

    @Nested
    @DisplayName("unresolvable lines")
    class UnresolvableLines {

        @Test
        @DisplayName("a line naming no GL account at all leaves the condition unresolved")
        void lineWithNoAccountReference() {
            stubPublishedRules("{\"conditions\":[{\"condition\":\"eventType == '" + EVENT_TYPE
                    + "'\",\"lines\":[{\"side\":\"DEBIT\",\"amountField\":\"payload.amount\"}]}]}");

            assertThat(evaluator.evaluateEvent(event("100.00"), null).getFailureReason())
                    .isEqualTo(PostingFailureReason.UNMAPPED_EVENT_TYPE);
        }

        @Test
        @DisplayName("a posting category without a mapping key is not a usable account reference")
        void postingCategoryWithoutMappingKey() {
            stubPublishedRules("{\"conditions\":[{\"condition\":\"eventType == '" + EVENT_TYPE
                    + "'\",\"lines\":[{\"postingCategoryId\":\"" + CATEGORY_ID
                    + "\",\"side\":\"DEBIT\",\"amountField\":\"payload.amount\"}]}]}");

            // Both halves are required to reach the mapping table; one alone resolves nothing, and
            // falling through to "no account" is what stops a half-configured line from posting.
            assertThat(evaluator.evaluateEvent(event("100.00"), null).getFailureReason())
                    .isEqualTo(PostingFailureReason.UNMAPPED_EVENT_TYPE);
        }
    }

    @Nested
    @DisplayName("rule-set shapes that yield no mapping")
    class NoMapping {

        @Test
        @DisplayName("an empty rules definition")
        void emptyRulesDefinition() {
            stubPublishedRules("{}");

            PostingResult result = evaluator.evaluateEvent(event("100.00"), null);

            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.UNMAPPED_EVENT_TYPE);
            assertThat(result.getEvaluationDetails()).containsEntry("failureStep", "evaluateMappingKeys");
        }

        @Test
        @DisplayName("a conditions key that is not an array")
        void conditionsNotAnArray() {
            stubPublishedRules("{\"conditions\":{\"condition\":\"*\"}}");

            assertThat(evaluator.evaluateEvent(event("100.00"), null).getFailureReason())
                    .isEqualTo(PostingFailureReason.UNMAPPED_EVENT_TYPE);
        }

        @Test
        @DisplayName("a matching condition carrying an empty lines array is skipped, not treated as a match")
        void matchedConditionWithNoLines() {
            stubPublishedRules("{\"conditions\":[{\"condition\":\"*\",\"lines\":[]},"
                    + "{\"condition\":\"*\",\"lines\":[" + line(ACCOUNT_A, "DEBIT", "payload.amount", "") + ","
                    + line(ACCOUNT_B, "CREDIT", "payload.amount", "") + "]}]}");

            // The first block matches but describes nothing to post; evaluation must fall through
            // to the next block rather than stop at a match that produces no lines.
            PostingResult result = evaluator.evaluateEvent(event("100.00"), null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getLines()).hasSize(2);
        }

        @Test
        @DisplayName("unparseable JSON is a failure, not an exception out of the evaluator")
        void unparseableRules() {
            stubPublishedRules("{\"conditions\": [ this is not json");

            assertThat(evaluator.evaluateEvent(event("100.00"), null).getFailureReason())
                    .isEqualTo(PostingFailureReason.UNMAPPED_EVENT_TYPE);
        }
    }

    @Nested
    @DisplayName("condition and line defaults")
    class Defaults {

        @Test
        @DisplayName("a condition block with no condition key is a catch-all, keyed as (default)")
        void conditionBlockWithoutACondition() {
            stubPublishedRules("{\"conditions\":[{\"lines\":[" + line(ACCOUNT_A, "DEBIT", "payload.amount", "") + ","
                    + line(ACCOUNT_B, "CREDIT", "payload.amount", "") + "]}]}");

            PostingResult result = evaluator.evaluateEvent(event("100.00"), null);

            assertThat(result.isSuccess()).isTrue();
            // With no expression to name it by, the mapping key falls back to the event type.
            assertThat(result.getEvaluationDetails()).containsEntry("mappingKey", EVENT_TYPE);
        }

        @Test
        @DisplayName("a line with no side is a debit")
        void lineWithoutASideIsADebit() {
            stubPublishedRules("{\"conditions\":[{\"condition\":\"*\",\"lines\":["
                    + "{\"glAccountId\":\"" + ACCOUNT_A + "\",\"amountField\":\"payload.amount\"},"
                    + line(ACCOUNT_B, "CREDIT", "payload.amount", "") + "]}]}");

            PostingResult result = evaluator.evaluateEvent(event("100.00"), null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getLines().get(0).getDebitAmount())
                    .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("a line naming no amount field posts zero rather than failing")
        void lineWithoutAnAmountFieldPostsZero() {
            stubPublishedRules("{\"conditions\":[{\"condition\":\"*\",\"lines\":["
                    + "{\"glAccountId\":\"" + ACCOUNT_A + "\",\"side\":\"DEBIT\"},"
                    + "{\"glAccountId\":\"" + ACCOUNT_B + "\",\"side\":\"CREDIT\"}]}]}");

            PostingResult result = evaluator.evaluateEvent(event("100.00"), null);

            // Zero on both sides still balances, so this posts an empty entry rather than being
            // rejected. Pinned as it stands: it is the pre-existing behaviour, and changing it
            // belongs in publish-time validation rather than here.
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getLines().get(0).getDebitAmount())
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("an amount field pointing outside the payload map resolves to zero")
        void amountFieldNavigatingOffTheMap() {
            stubPublishedRules(rules(
                    line(ACCOUNT_A, "DEBIT", "payload.amount.deeper", ""),
                    line(ACCOUNT_B, "CREDIT", "payload.amount.deeper", "")));

            PostingResult result = evaluator.evaluateEvent(event("100.00"), null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getLines().get(0).getDebitAmount())
                    .isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("version selection")
    class VersionSelection {

        @Test
        @DisplayName("an explicitly requested version is loaded by id, bypassing event-type lookup")
        void explicitVersionIsLoadedById() {
            PostingRuleVersion version = version(rules(
                    line(ACCOUNT_A, "DEBIT", "payload.amount", ""), line(ACCOUNT_B, "CREDIT", "payload.amount", "")));
            when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));

            PostingResult result = evaluator.evaluateEvent(event("100.00"), VERSION_ID);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails()).containsEntry("ruleVersionUsed", VERSION_ID);
        }

        @Test
        @DisplayName("an explicitly requested version that does not exist is not silently replaced")
        void explicitVersionThatIsMissing() {
            when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.empty());

            PostingResult result = evaluator.evaluateEvent(event("100.00"), VERSION_ID);

            // Falling back to whatever is published for the event type would post under a rule set
            // the caller did not ask for -- exactly what naming a version is meant to prevent.
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.NO_RULE_VERSION);
        }
    }

    @Nested
    @DisplayName("events and versions with pieces missing")
    class MissingPieces {

        @Test
        @DisplayName("an event with no payload resolves every amount to zero rather than failing")
        void eventWithNoPayload() {
            stubPublishedRules(rules(
                    line(ACCOUNT_A, "DEBIT", "payload.amount", ""), line(ACCOUNT_B, "CREDIT", "payload.amount", "")));
            AccountingEvent event = event("100.00");
            event.setPayload(null);

            PostingResult result = evaluator.evaluateEvent(event, null);

            // No payload means no dimensions to resolve GL mappings against and no amount to post.
            // Zero on both sides still balances, so this succeeds as an empty entry rather than
            // throwing on the way past.
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getLines().get(0).getDebitAmount())
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a rule version detached from its rule set still produces an entry")
        void versionWithoutARuleSet() {
            PostingRuleVersion version = version(rules(
                    line(ACCOUNT_A, "DEBIT", "payload.amount", ""), line(ACCOUNT_B, "CREDIT", "payload.amount", "")));
            version.setPostingRuleSet(null);
            when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));

            PostingResult result = evaluator.evaluateEvent(event("100.00"), VERSION_ID);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getPostingRuleSetId()).isNull();
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private PostingRuleVersion version(String rulesDefinition) {
        PostingRuleSet ruleSet = new PostingRuleSet();
        ruleSet.setPostingRuleSetId(RULE_SET_ID);
        ruleSet.setName("Failure-path rules");
        ruleSet.setEventType(EVENT_TYPE);

        PostingRuleVersion version = new PostingRuleVersion();
        version.setVersionId(VERSION_ID);
        version.setVersionNumber(1);
        version.setState(PostingRuleSetState.PUBLISHED);
        version.setRulesDefinition(rulesDefinition);
        version.setPostingRuleSet(ruleSet);
        return version;
    }

    private void stubPublishedRules(String rulesDefinition) {
        PostingRuleVersion version = version(rulesDefinition);
        when(ruleSetRepository.findByEventType(EVENT_TYPE)).thenReturn(List.of(version.getPostingRuleSet()));
        when(versionRepository.findByPostingRuleSet_PostingRuleSetIdAndState(
                        RULE_SET_ID, PostingRuleSetState.PUBLISHED))
                .thenReturn(List.of(version));
    }

    private static AccountingEvent event(String amount) {
        return event(amount, null);
    }

    private static AccountingEvent event(String amount, String other) {
        AccountingEvent event = new AccountingEvent();
        event.setEventId(UUID.fromString("00000000-0000-0000-0000-0000000000e1"));
        event.setOrganizationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        event.setEventType(EVENT_TYPE);
        event.setTransactionDate(LocalDateTime.of(2026, 1, 15, 10, 30));
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        if (other != null) {
            payload.put("other", other);
        }
        event.setPayload(payload);
        event.setReceivedAt(Instant.now(TEST_CLOCK));
        event.setSourceSystem("TEST_SYSTEM");
        return event;
    }

    private static String line(UUID account, String side, String amountField, String extraJson) {
        return "{\"glAccountId\":\"" + account + "\",\"side\":\"" + side + "\",\"amountField\":\"" + amountField + "\""
                + (extraJson.isEmpty() ? "" : "," + extraJson) + "}";
    }

    private static String rules(String... lines) {
        return "{\"conditions\":[{\"condition\":\"eventType == '" + EVENT_TYPE + "'\",\"lines\":["
                + String.join(",", lines) + "]}]}";
    }
}
