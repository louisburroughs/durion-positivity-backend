package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.dto.PostingRuleSetCreateRequest;
import com.positivity.accounting.internal.dto.PostingRuleSetResponse;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.exception.UnbalancedRulesException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * H2-backed end-to-end test for E2 condition predicates (issue #946): a
 * rule set whose conditions discriminate on {@code payload.paymentMethod}
 * is created and published through {@link PostingRuleService} (exercising
 * the publish-time predicate validation on the real persistence path), then
 * the {@link PostingRuleEvaluator} loads it from the database and routes
 * CASH and CARD events to different GL accounts. A malformed predicate must
 * be rejected at publish, never reaching evaluation.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@Import(TestSecurityConfig.class)
@DisplayName("Posting rule predicate lifecycle (E2, H2 end-to-end)")
class PostingRulePredicateLifecycleTest {

    private static final String EVENT_TYPE = "parity.e2.paymentReceived";

    private static final String CASH_DEBIT = "00000000-0000-0000-0000-0000000000d1";
    private static final String CARD_DEBIT = "00000000-0000-0000-0000-0000000000d2";
    private static final String PREMIUM_DEBIT = "00000000-0000-0000-0000-0000000000d3";
    private static final String FALLBACK_DEBIT = "00000000-0000-0000-0000-0000000000d4";
    private static final String CREDIT_ACCOUNT = "00000000-0000-0000-0000-0000000000d9";

    @Autowired
    private PostingRuleService postingRuleService;

    @Autowired
    private PostingRuleEvaluator postingRuleEvaluator;

    private static String conditionBlock(String conditionExpr, String debitAccount) {
        return "{\"condition\":\"" + conditionExpr + "\",\"lines\":["
                + "{\"glAccountId\":\"" + debitAccount + "\",\"side\":\"DEBIT\","
                + "\"amountField\":\"payload.amount\",\"description\":\"Routed debit\"},"
                + "{\"glAccountId\":\"" + CREDIT_ACCOUNT + "\",\"side\":\"CREDIT\","
                + "\"amountField\":\"payload.amount\",\"description\":\"Clearing\"}"
                + "]}";
    }

    private static String rules(String... conditionBlocks) {
        return "{\"conditions\":[" + String.join(",", conditionBlocks) + "]}";
    }

    private UUID createRuleSet(String rulesDefinition) {
        PostingRuleSetResponse created =
                postingRuleService.createPostingRuleSetWithVersion(PostingRuleSetCreateRequest.builder()
                        .name("E2 predicate lifecycle rules")
                        .eventType(EVENT_TYPE)
                        .description("Story E2 end-to-end predicate rules")
                        .rulesDefinition(rulesDefinition)
                        .createdBy("e2-test")
                        .build());
        return created.getPostingRuleSetId();
    }

    private AccountingEvent createEvent(String amount, String paymentMethod) {
        AccountingEvent event = new AccountingEvent();
        event.setEventId(UUID.randomUUID());
        event.setOrganizationId(UUID.randomUUID());
        event.setEventType(EVENT_TYPE);
        event.setTransactionDate(LocalDateTime.of(2026, 2, 20, 9, 45));
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        payload.put("paymentMethod", paymentMethod);
        event.setPayload(payload);
        event.setReceivedAt(Instant.now());
        event.setSourceSystem("E2_TEST");
        return event;
    }

    private UUID debitAccountOf(PostingResult result) {
        assertThat(result.isSuccess()).as("evaluation success").isTrue();
        JournalEntry entry = result.getJournalEntryDraft();
        assertThat(entry).isNotNull();
        assertThat(entry.getTotalDebits()).isEqualByComparingTo(entry.getTotalCredits());
        return entry.getLines().get(0).getGlAccountId();
    }

    @Test
    @DisplayName("published paymentMethod predicates route CASH and CARD to different accounts")
    void paymentMethodPredicatesRouteToDifferentAccounts() {
        UUID ruleSetId = createRuleSet(rules(
                conditionBlock("payload.paymentMethod == 'CASH'", CASH_DEBIT),
                conditionBlock("payload.paymentMethod == 'CARD'", CARD_DEBIT)));
        postingRuleService.publishRuleSet(ruleSetId);

        assertThat(debitAccountOf(postingRuleEvaluator.evaluateEvent(createEvent("75.00", "CASH"), null)))
                .isEqualTo(UUID.fromString(CASH_DEBIT));
        assertThat(debitAccountOf(postingRuleEvaluator.evaluateEvent(createEvent("75.00", "CARD"), null)))
                .isEqualTo(UUID.fromString(CARD_DEBIT));

        // Payment method matching neither condition finds no rule → failure
        assertThat(postingRuleEvaluator
                        .evaluateEvent(createEvent("75.00", "CHECK"), null)
                        .isSuccess())
                .isFalse();
    }

    @Test
    @DisplayName("published conjunction predicate with numeric threshold routes first-match-wins over a catch-all")
    void conjunctionThresholdRoutesFirstMatchWins() {
        UUID ruleSetId = createRuleSet(rules(
                conditionBlock("payload.amount >= 100.00 && eventType == '" + EVENT_TYPE + "'", PREMIUM_DEBIT),
                conditionBlock("*", FALLBACK_DEBIT)));
        postingRuleService.publishRuleSet(ruleSetId);

        assertThat(debitAccountOf(postingRuleEvaluator.evaluateEvent(createEvent("150.00", "CASH"), null)))
                .isEqualTo(UUID.fromString(PREMIUM_DEBIT));
        assertThat(debitAccountOf(postingRuleEvaluator.evaluateEvent(createEvent("50.00", "CASH"), null)))
                .isEqualTo(UUID.fromString(FALLBACK_DEBIT));
    }

    @Test
    @DisplayName("publishing a malformed predicate is rejected end-to-end with a conditions[i].condition locator")
    void publishingMalformedPredicateRejected() {
        UUID ruleSetId = createRuleSet(rules(conditionBlock("payload.amount >>> 'garbage", CASH_DEBIT)));

        assertThatThrownBy(() -> postingRuleService.publishRuleSet(ruleSetId))
                .isInstanceOf(UnbalancedRulesException.class)
                .hasMessageContaining("conditions[0].condition")
                .hasMessageContaining("condition predicate is invalid");

        // The draft stays unpublished, so the evaluator finds no rules
        PostingResult result = postingRuleEvaluator.evaluateEvent(createEvent("100.00", "CASH"), null);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("publishing an ordering operator with a string literal is rejected end-to-end")
    void publishingOrderingOpWithStringRejected() {
        UUID ruleSetId = createRuleSet(rules(conditionBlock("payload.amount > 'high'", CASH_DEBIT)));

        assertThatThrownBy(() -> postingRuleService.publishRuleSet(ruleSetId))
                .isInstanceOf(UnbalancedRulesException.class)
                .hasMessageContaining("conditions[0].condition")
                .hasMessageContaining("numeric literal");
    }
}
