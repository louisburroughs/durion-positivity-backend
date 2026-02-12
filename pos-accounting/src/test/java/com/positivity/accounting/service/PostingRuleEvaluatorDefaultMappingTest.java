package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
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

import com.positivity.accounting.internal.config.DefaultGLMappingProperties;
import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.DefaultGLMapping;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.PostingRuleSet;
import com.positivity.accounting.internal.entity.PostingRuleVersion;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.JournalEntryType;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.enums.PostingRuleSetState;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.PostingRuleSetRepository;
import com.positivity.accounting.internal.repository.PostingRuleVersionRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for PostingRuleEvaluatorImpl default mapping fallback logic.
 *
 * <p>
 * Validates the journal entry generation mechanics when the evaluator falls
 * back to {@link DefaultGLMapping}: amount resolution, description formatting,
 * GL account assignment, journal entry metadata, and the interaction with
 * explicit posting rules.
 * </p>
 *
 * <p>
 * Feature flag behaviour (enabled/disabled switches) is covered by
 * {@link PostingRuleEvaluatorFeatureFlagTest}.
 * </p>
 */
@Disabled("Pending resolution of PostingResult API changes")
class PostingRuleEvaluatorDefaultMappingTest {

    @Mock
    private PostingRuleVersionRepository versionRepository;

    @Mock
    private PostingRuleSetRepository ruleSetRepository;

    @Mock
    private GLMappingResolver glMappingResolver;

    @Mock
    private DefaultGLMappingRepository defaultGLMappingRepository;

    private DefaultGLMappingProperties defaultGLMappingProperties;

    private PostingRuleEvaluatorImpl evaluator;

    private UUID testOrganizationId;
    private UUID testDebitAccountId;
    private UUID testCreditAccountId;

    @BeforeEach
    void setUp() {
        defaultGLMappingProperties = new DefaultGLMappingProperties();
        defaultGLMappingProperties.setEnabled(true);
        defaultGLMappingProperties.setAllowGlobalDefaults(true);
        defaultGLMappingProperties.setRequireAmountField(false);

        evaluator = new PostingRuleEvaluatorImpl(
                versionRepository,
                ruleSetRepository,
                glMappingResolver,
                defaultGLMappingRepository,
                defaultGLMappingProperties,
                new ObjectMapper());

        testOrganizationId = UUID.randomUUID();
        testDebitAccountId = UUID.randomUUID();
        testCreditAccountId = UUID.randomUUID();
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    private AccountingEvent createEvent(String eventType, Map<String, Object> payload) {
        AccountingEvent event = new AccountingEvent();
        event.setEventId(UUID.randomUUID());
        event.setOrganizationId(testOrganizationId);
        event.setEventType(eventType);
        event.setTransactionDate(LocalDateTime.of(2026, 1, 15, 10, 30));
        event.setPayload(payload != null ? payload : new HashMap<>());
        event.setReceivedAt(Instant.now());
        event.setSourceSystem("TEST_SYSTEM");
        return event;
    }

    private AccountingEvent createEventWithAmount(String eventType, String amount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        return createEvent(eventType, payload);
    }

    private DefaultGLMapping createDefaultMapping(String eventType, String description) {
        DefaultGLMapping mapping = new DefaultGLMapping();
        mapping.setMappingId(UUID.randomUUID());
        mapping.setEventType(eventType);
        mapping.setOrganizationId(testOrganizationId);
        mapping.setDebitAccountId(testDebitAccountId);
        mapping.setCreditAccountId(testCreditAccountId);
        mapping.setDescription(description);
        mapping.setActive(true);
        return mapping;
    }

    private void stubNoPostingRules() {
        when(ruleSetRepository.findByEventType(anyString()))
                .thenReturn(Collections.emptyList());
    }

    private void stubDefaultMapping(String eventType, DefaultGLMapping mapping) {
        when(defaultGLMappingRepository.findActiveDefaultForEvent(eventType, testOrganizationId))
                .thenReturn(Optional.of(mapping));
    }

    // ── Test groups ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Journal entry metadata")
    class JournalEntryMetadataTests {

        @Test
        @DisplayName("Should set sourceEventId from accounting event")
        void shouldSetSourceEventId() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Invoice posting");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getSourceEventId()).isEqualTo(event.getEventId());
        }

        @Test
        @DisplayName("Should set sourceEventType from accounting event")
        void shouldSetSourceEventType() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Invoice posting");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getSourceEventType()).isEqualTo("billing.invoicePosted");
        }

        @Test
        @DisplayName("Should set transactionDate from accounting event")
        void shouldSetTransactionDate() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Invoice posting");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getTransactionDate())
                    .isEqualTo(event.getTransactionDate());
        }

        @Test
        @DisplayName("Should set DRAFT status on generated journal entry")
        void shouldSetDraftStatus() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Invoice posting");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getStatus()).isEqualTo(JournalEntryStatus.DRAFT);
        }

        @Test
        @DisplayName("Should set EVENT_DRIVEN entry type")
        void shouldSetEventDrivenEntryType() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Invoice posting");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getEntryType()).isEqualTo(JournalEntryType.EVENT_DRIVEN);
        }

        @Test
        @DisplayName("Should set null posting rule references since no rule was used")
        void shouldSetNullPostingRuleReferences() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Invoice posting");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getPostingRuleVersionId()).isNull();
            assertThat(result.getJournalEntryDraft().getPostingRuleSetId()).isNull();
            assertThat(result.getMappingVersionUsed()).isNull();
        }

        @Test
        @DisplayName("Should include auto-generated description referencing event type")
        void shouldSetAutoGeneratedDescription() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Invoice posting");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getDescription())
                    .contains("default GL mapping")
                    .contains("billing.invoicePosted");
        }

        @Test
        @DisplayName("Should generate unique journal entry ID (UUIDv7)")
        void shouldGenerateUniqueJournalEntryId() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Invoice posting");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getJournalEntryId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Journal entry line generation")
    class JournalEntryLineTests {

        @Test
        @DisplayName("Should generate exactly two lines (debit + credit)")
        void shouldGenerateTwoLines() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Invoice posting");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "500.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getLines()).hasSize(2);
        }

        @Test
        @DisplayName("Should assign debit account from default mapping to first line")
        void shouldAssignDebitAccountToFirstLine() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Invoice posting");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "500.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            JournalEntryLine debitLine = result.getJournalEntryDraft().getLines().get(0);
            assertThat(debitLine.getGlAccountId()).isEqualTo(testDebitAccountId);
            assertThat(debitLine.getDebitAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
            assertThat(debitLine.getCreditAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Should assign credit account from default mapping to second line")
        void shouldAssignCreditAccountToSecondLine() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Invoice posting");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "500.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            JournalEntryLine creditLine = result.getJournalEntryDraft().getLines().get(1);
            assertThat(creditLine.getGlAccountId()).isEqualTo(testCreditAccountId);
            assertThat(creditLine.getDebitAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(creditLine.getCreditAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
        }

        @Test
        @DisplayName("Should produce balanced entry where total debits equal total credits")
        void shouldProduceBalancedEntry() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Invoice posting");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "1234.56");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            JournalEntry entry = result.getJournalEntryDraft();

            BigDecimal totalDebits = entry.getLines().stream()
                    .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCredits = entry.getLines().stream()
                    .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(totalDebits).isEqualByComparingTo(totalCredits);
            assertThat(totalDebits).isEqualByComparingTo(new BigDecimal("1234.56"));
        }

        @Test
        @DisplayName("Should set journal entry ID on each line")
        void shouldSetJournalEntryIdOnLines() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Invoice posting");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            UUID journalEntryId = result.getJournalEntryDraft().getJournalEntryId();
            for (JournalEntryLine line : result.getJournalEntryDraft().getLines()) {
                assertThat(line.getJournalEntryId()).isEqualTo(journalEntryId);
                assertThat(line.getLineId()).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("Line description formatting")
    class LineDescriptionTests {

        @Test
        @DisplayName("Should append (DR) suffix to debit line when mapping has description")
        void shouldAppendDrSuffixToDebitLine() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Revenue recognition");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getLines().get(0).getDescription())
                    .isEqualTo("Revenue recognition (DR)");
        }

        @Test
        @DisplayName("Should append (CR) suffix to credit line when mapping has description")
        void shouldAppendCrSuffixToCreditLine() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Revenue recognition");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getLines().get(1).getDescription())
                    .isEqualTo("Revenue recognition (CR)");
        }

        @Test
        @DisplayName("Should use fallback description when mapping has null description")
        void shouldUseFallbackDescriptionWhenNull() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", null);
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            List<JournalEntryLine> lines = result.getJournalEntryDraft().getLines();
            assertThat(lines.get(0).getDescription())
                    .isEqualTo("Default debit for billing.invoicePosted");
            assertThat(lines.get(1).getDescription())
                    .isEqualTo("Default credit for billing.invoicePosted");
        }
    }

    @Nested
    @DisplayName("Amount resolution")
    class AmountResolutionTests {

        @Test
        @DisplayName("Should resolve amount from payload.amount as String")
        void shouldResolveAmountFromStringPayload() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Test");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "750.25");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getLines().get(0).getDebitAmount())
                    .isEqualByComparingTo(new BigDecimal("750.25"));
        }

        @Test
        @DisplayName("Should resolve amount from payload.amount as Number")
        void shouldResolveAmountFromNumericPayload() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Test");
            stubDefaultMapping("billing.invoicePosted", mapping);

            Map<String, Object> payload = new HashMap<>();
            payload.put("amount", 425.50);
            AccountingEvent event = createEvent("billing.invoicePosted", payload);

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getLines().get(0).getDebitAmount())
                    .isEqualByComparingTo(new BigDecimal("425.5"));
        }

        @Test
        @DisplayName("Should use 0.01 fallback when amount is missing and requireAmountField=false")
        void shouldUseFallbackAmountWhenMissing() {
            defaultGLMappingProperties.setRequireAmountField(false);
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Test");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEvent("billing.invoicePosted", new HashMap<>());

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getLines().get(0).getDebitAmount())
                    .isEqualByComparingTo(new BigDecimal("0.01"));
            assertThat(result.getJournalEntryDraft().getLines().get(1).getCreditAmount())
                    .isEqualByComparingTo(new BigDecimal("0.01"));
        }

        @Test
        @DisplayName("Should handle large monetary amounts correctly")
        void shouldHandleLargeAmounts() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Test");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "9999999.9999");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getLines().get(0).getDebitAmount())
                    .isEqualByComparingTo(new BigDecimal("9999999.9999"));
        }

        @Test
        @DisplayName("Should handle small fractional amounts correctly")
        void shouldHandleSmallFractionalAmounts() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Test");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "0.01");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getLines().get(0).getDebitAmount())
                    .isEqualByComparingTo(new BigDecimal("0.01"));
        }
    }

    @Nested
    @DisplayName("Evaluation details")
    class EvaluationDetailsTests {

        @Test
        @DisplayName("Should include mappingType=defaultGLMapping in evaluation details")
        void shouldIncludeMappingType() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Test");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails())
                    .containsEntry("mappingType", "defaultGLMapping");
        }

        @Test
        @DisplayName("Should include defaultMappingId in evaluation details")
        void shouldIncludeDefaultMappingId() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Test");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails())
                    .containsEntry("defaultMappingId", mapping.getMappingId());
        }

        @Test
        @DisplayName("Should include journalEntryLineCount in evaluation details")
        void shouldIncludeLineCount() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Test");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails())
                    .containsEntry("journalEntryLineCount", 2);
        }

        @Test
        @DisplayName("Should include journalEntryAmount matching the event amount")
        void shouldIncludeJournalEntryAmount() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Test");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "888.88");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails()).containsKey("journalEntryAmount");
            assertThat((BigDecimal) result.getEvaluationDetails().get("journalEntryAmount"))
                    .isEqualByComparingTo(new BigDecimal("888.88"));
        }

        @Test
        @DisplayName("Should include eventId and eventType in evaluation details")
        void shouldIncludeEventContext() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Test");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails())
                    .containsEntry("eventId", event.getEventId())
                    .containsEntry("eventType", "billing.invoicePosted")
                    .containsEntry("organizationId", testOrganizationId);
        }
    }

    @Nested
    @DisplayName("Fallback priority (posting rules vs default mappings)")
    class FallbackPriorityTests {

        @Test
        @DisplayName("Should prefer explicit posting rule over default mapping when rule exists")
        void shouldPreferExplicitPostingRule() {
            // Arrange: set up a posting rule set with a published version
            UUID ruleSetId = UUID.randomUUID();
            PostingRuleSet ruleSet = new PostingRuleSet();
            ruleSet.setPostingRuleSetId(ruleSetId);
            ruleSet.setName("Invoice Rule Set");
            ruleSet.setEventType("billing.invoicePosted");

            UUID versionId = UUID.randomUUID();
            PostingRuleVersion ruleVersion = new PostingRuleVersion();
            ruleVersion.setVersionId(versionId);
            ruleVersion.setPostingRuleSet(ruleSet);
            ruleVersion.setVersionNumber(1);
            ruleVersion.setState(PostingRuleSetState.PUBLISHED);
            // Minimal valid rulesDefinition with a direct glAccountId
            ruleVersion.setRulesDefinition("""
                    {
                      "conditions": [
                        {
                          "condition": "*",
                          "lines": [
                            { "side": "DEBIT", "glAccountId": "%s", "amountField": "payload.amount" },
                            { "side": "CREDIT", "glAccountId": "%s", "amountField": "payload.amount" }
                          ]
                        }
                      ]
                    }
                    """.formatted(UUID.randomUUID(), UUID.randomUUID()));

            when(ruleSetRepository.findByEventType("billing.invoicePosted"))
                    .thenReturn(List.of(ruleSet));
            when(versionRepository.findByPostingRuleSet_PostingRuleSetIdAndState(
                    ruleSetId, PostingRuleSetState.PUBLISHED))
                    .thenReturn(List.of(ruleVersion));

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "500.00");

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert — should use explicit rule, not default mapping
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMappingVersionUsed()).isEqualTo(versionId);
            assertThat(result.getEvaluationDetails())
                    .containsEntry("ruleVersionUsed", versionId)
                    .doesNotContainKey("defaultMappingId");
        }

        @Test
        @DisplayName("Should fall back to default mapping when no rule sets exist for event type")
        void shouldFallbackWhenNoRuleSets() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Fallback");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails())
                    .containsEntry("mappingType", "defaultGLMapping");
        }

        @Test
        @DisplayName("Should fall back to default mapping when rule sets exist but no PUBLISHED versions")
        void shouldFallbackWhenNoPublishedVersions() {
            UUID ruleSetId = UUID.randomUUID();
            PostingRuleSet ruleSet = new PostingRuleSet();
            ruleSet.setPostingRuleSetId(ruleSetId);
            ruleSet.setEventType("billing.invoicePosted");

            when(ruleSetRepository.findByEventType("billing.invoicePosted"))
                    .thenReturn(List.of(ruleSet));
            when(versionRepository.findByPostingRuleSet_PostingRuleSetIdAndState(
                    ruleSetId, PostingRuleSetState.PUBLISHED))
                    .thenReturn(Collections.emptyList());

            DefaultGLMapping mapping = createDefaultMapping("billing.invoicePosted", "Fallback");
            stubDefaultMapping("billing.invoicePosted", mapping);

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails())
                    .containsEntry("mappingType", "defaultGLMapping");
        }

        @Test
        @DisplayName("Should fail when no posting rules AND no default mapping exist")
        void shouldFailWhenNothingExists() {
            stubNoPostingRules();
            when(defaultGLMappingRepository.findActiveDefaultForEvent(anyString(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Optional.empty());

            AccountingEvent event = createEventWithAmount("unknown.eventType", "100.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.NO_RULE_VERSION);
            assertThat(result.getFailureDetails())
                    .contains("unknown.eventType");
        }
    }

    @Nested
    @DisplayName("Input validation before default mapping")
    class InputValidationTests {

        @Test
        @DisplayName("Should fail with VALIDATION_ERROR when organizationId is null")
        void shouldFailWhenOrgIdNull() {
            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");
            event.setOrganizationId(null);

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.VALIDATION_ERROR);
            assertThat(result.getFailureDetails()).contains("organizationId");
        }

        @Test
        @DisplayName("Should fail with VALIDATION_ERROR when transactionDate is null")
        void shouldFailWhenTransactionDateNull() {
            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");
            event.setTransactionDate(null);

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.VALIDATION_ERROR);
            assertThat(result.getFailureDetails()).contains("transactionDate");
        }

        @Test
        @DisplayName("Should fail with VALIDATION_ERROR when eventType is null")
        void shouldFailWhenEventTypeNull() {
            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");
            event.setEventType(null);

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.VALIDATION_ERROR);
            assertThat(result.getFailureDetails()).contains("eventType");
        }

        @Test
        @DisplayName("Should fail with VALIDATION_ERROR when eventType is blank")
        void shouldFailWhenEventTypeBlank() {
            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");
            event.setEventType("   ");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.VALIDATION_ERROR);
        }
    }

    @Nested
    @DisplayName("Different event types")
    class DifferentEventTypeTests {

        @Test
        @DisplayName("Should resolve default mapping for payment event type")
        void shouldResolvePaymentEventType() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("payment.received", "Payment received");
            when(defaultGLMappingRepository.findActiveDefaultForEvent("payment.received", testOrganizationId))
                    .thenReturn(Optional.of(mapping));

            AccountingEvent event = createEventWithAmount("payment.received", "2500.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getSourceEventType()).isEqualTo("payment.received");
            assertThat(result.getJournalEntryDraft().getLines().get(0).getDebitAmount())
                    .isEqualByComparingTo(new BigDecimal("2500.00"));
        }

        @Test
        @DisplayName("Should resolve default mapping for refund event type")
        void shouldResolveRefundEventType() {
            stubNoPostingRules();
            DefaultGLMapping mapping = createDefaultMapping("refund.issued", "Refund issued");
            when(defaultGLMappingRepository.findActiveDefaultForEvent("refund.issued", testOrganizationId))
                    .thenReturn(Optional.of(mapping));

            AccountingEvent event = createEventWithAmount("refund.issued", "150.00");

            PostingResult result = evaluator.evaluateEvent(event);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getDescription())
                    .contains("refund.issued");
            assertThat(result.getJournalEntryDraft().getLines().get(0).getDescription())
                    .isEqualTo("Refund issued (DR)");
            assertThat(result.getJournalEntryDraft().getLines().get(1).getDescription())
                    .isEqualTo("Refund issued (CR)");
        }
    }
}
