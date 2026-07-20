package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.dto.PostingRuleSetCreateRequest;
import com.positivity.accounting.internal.dto.PostingRuleSetResponse;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.exception.UnbalancedRulesException;
import java.math.BigDecimal;
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
 * H2-backed end-to-end test for E1 proportional split lines (issue #945):
 * a split rule set is created and published through
 * {@link PostingRuleService} (exercising the publish-time split validation
 * on the real persistence path), then the {@link PostingRuleEvaluator} loads
 * it from the database and produces a balanced journal entry whose split
 * lines sum exactly to the source amount.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@Import(TestSecurityConfig.class)
@DisplayName("Posting rule split lifecycle (E1, H2 end-to-end)")
class PostingRuleSplitLifecycleTest {

    private static final String EVENT_TYPE = "parity.e1.laborCharge";

    private static final String DEBIT_ACCOUNT_A = "00000000-0000-0000-0000-0000000000b1";
    private static final String DEBIT_ACCOUNT_B = "00000000-0000-0000-0000-0000000000b2";
    private static final String CREDIT_ACCOUNT = "00000000-0000-0000-0000-0000000000b3";

    @Autowired
    private PostingRuleService postingRuleService;

    @Autowired
    private PostingRuleEvaluator postingRuleEvaluator;

    private static String splitRules(String factor1, String factor2) {
        return "{\"conditions\":[{\"condition\":\"eventType == '" + EVENT_TYPE + "'\",\"lines\":["
                + "{\"glAccountId\":\"" + DEBIT_ACCOUNT_A + "\",\"side\":\"DEBIT\","
                + "\"amountField\":\"payload.amount\",\"factorPercent\":" + factor1 + ",\"splitGroup\":\"labor\","
                + "\"description\":\"Labor cost pool A\"},"
                + "{\"glAccountId\":\"" + DEBIT_ACCOUNT_B + "\",\"side\":\"DEBIT\","
                + "\"amountField\":\"payload.amount\",\"factorPercent\":" + factor2 + ",\"splitGroup\":\"labor\","
                + "\"description\":\"Labor cost pool B\"},"
                + "{\"glAccountId\":\"" + CREDIT_ACCOUNT + "\",\"side\":\"CREDIT\","
                + "\"amountField\":\"payload.amount\",\"description\":\"Accrued labor\"}"
                + "]}]}";
    }

    private UUID createRuleSet(String rulesDefinition) {
        PostingRuleSetResponse created =
                postingRuleService.createPostingRuleSetWithVersion(PostingRuleSetCreateRequest.builder()
                        .name("E1 split lifecycle rules")
                        .eventType(EVENT_TYPE)
                        .description("Story E1 end-to-end split rules")
                        .rulesDefinition(rulesDefinition)
                        .createdBy("e1-test")
                        .build());
        return created.getPostingRuleSetId();
    }

    private AccountingEvent createEvent(String amount) {
        AccountingEvent event = new AccountingEvent();
        event.setEventId(UUID.randomUUID());
        event.setOrganizationId(UUID.randomUUID());
        event.setEventType(EVENT_TYPE);
        event.setTransactionDate(LocalDateTime.of(2026, 1, 15, 10, 30));
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        event.setPayload(payload);
        event.setReceivedAt(Instant.now());
        event.setSourceSystem("E1_TEST");
        return event;
    }

    @Test
    @DisplayName("published 60/40 split rule produces a balanced journal entry summing exactly to the source")
    void publishedSplitRuleProducesBalancedJournalEntry() {
        UUID ruleSetId = createRuleSet(splitRules("60", "40"));
        postingRuleService.publishRuleSet(ruleSetId);

        PostingResult result = postingRuleEvaluator.evaluateEvent(createEvent("100.00"), null);

        assertThat(result.isSuccess()).isTrue();
        JournalEntry entry = result.getJournalEntryDraft();
        assertThat(entry).isNotNull();
        assertThat(entry.getLines()).hasSize(3);

        JournalEntryLine poolA = entry.getLines().get(0);
        JournalEntryLine poolB = entry.getLines().get(1);
        JournalEntryLine accrual = entry.getLines().get(2);

        assertThat(poolA.getGlAccountId()).isEqualTo(UUID.fromString(DEBIT_ACCOUNT_A));
        assertThat(poolA.getDebitAmount()).isEqualByComparingTo("60.00");
        assertThat(poolB.getGlAccountId()).isEqualTo(UUID.fromString(DEBIT_ACCOUNT_B));
        assertThat(poolB.getDebitAmount()).isEqualByComparingTo("40.00");
        assertThat(accrual.getCreditAmount()).isEqualByComparingTo("100.00");

        assertThat(entry.getTotalDebits()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(entry.getTotalCredits()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("publishing an unbalanced split group is rejected end-to-end with UNBALANCED_RULES semantics")
    void publishingUnbalancedSplitGroupRejected() {
        UUID ruleSetId = createRuleSet(splitRules("60", "30"));

        assertThatThrownBy(() -> postingRuleService.publishRuleSet(ruleSetId))
                .isInstanceOf(UnbalancedRulesException.class)
                .hasMessageContaining("splitGroup[labor]")
                .hasMessageContaining("sum to exactly 100");

        // The draft stays unpublished, so the evaluator finds no rules
        PostingResult result = postingRuleEvaluator.evaluateEvent(createEvent("100.00"), null);
        assertThat(result.isSuccess()).isFalse();
    }
}
