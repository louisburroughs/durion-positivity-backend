package com.positivity.accounting.service;

import java.time.ZoneOffset;
import java.time.Clock;

import com.positivity.accounting.internal.service.PostingRuleEvaluatorImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
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
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.PostingRuleSetRepository;
import com.positivity.accounting.internal.repository.PostingRuleVersionRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Feature flag tests for PostingRuleEvaluator with DefaultGLMapping fallback.
 *
 * <p>
 * Validates that the three feature flags in {@link DefaultGLMappingProperties}
 * control default GL mapping fallback behaviour correctly:
 * </p>
 * <ul>
 * <li>{@code enabled} — master switch for default mapping fallback</li>
 * <li>{@code allowGlobalDefaults} — whether org‑agnostic defaults are
 * permitted</li>
 * <li>{@code requireAmountField} — whether payload.amount is mandatory</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PostingRuleEvaluatorFeatureFlagTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);


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
        event.setTransactionDate(LocalDateTime.now(TEST_CLOCK));
        event.setPayload(payload != null ? payload : new HashMap<>());
        event.setReceivedAt(Instant.now(TEST_CLOCK));
        event.setSourceSystem("TEST_SYSTEM");
        return event;
    }

    private AccountingEvent createEventWithAmount(String eventType, String amount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        return createEvent(eventType, payload);
    }

    private DefaultGLMapping createDefaultMapping(UUID organizationId) {
        DefaultGLMapping mapping = new DefaultGLMapping();
        mapping.setMappingId(UUID.randomUUID());
        mapping.setEventType("billing.invoicePosted");
        mapping.setOrganizationId(organizationId);
        mapping.setDebitAccountId(testDebitAccountId);
        mapping.setCreditAccountId(testCreditAccountId);
        mapping.setDescription("Test default GL mapping");
        mapping.setActive(true);
        return mapping;
    }

    /**
     * Stubs repositories so no explicit posting rules exist for the event type,
     * forcing the evaluator into the default‑mapping fallback path.
     */
    private void stubNoPostingRules() {
        when(ruleSetRepository.findByEventType(anyString()))
                .thenReturn(Collections.emptyList());
    }

    // ── Test groups ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("enabled flag (master switch)")
    class EnabledFlagTests {

        @Test
        @DisplayName("Should use default mapping fallback when enabled=true and no posting rules exist")
        void shouldFallbackToDefaultMappingWhenEnabled() {
            // Arrange
            defaultGLMappingProperties.setEnabled(true);
            defaultGLMappingProperties.setAllowGlobalDefaults(true);
            defaultGLMappingProperties.setRequireAmountField(false);
            stubNoPostingRules();

            DefaultGLMapping mapping = createDefaultMapping(testOrganizationId);
            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    "billing.invoicePosted", testOrganizationId))
                    .thenReturn(Optional.of(mapping));

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "500.00");

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft()).isNotNull();
            assertThat(result.getEvaluationDetails())
                    .containsEntry("mappingType", "defaultGLMapping")
                    .containsKey("defaultMappingId");
            assertThat(result.getJournalEntryDraft().getLines()).hasSize(2);
        }

        @Test
        @DisplayName("Should fail with NO_RULE_VERSION when enabled=false and no posting rules exist")
        void shouldFailWhenDisabledAndNoPostingRules() {
            // Arrange
            defaultGLMappingProperties.setEnabled(false);
            stubNoPostingRules();

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "500.00");

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.NO_RULE_VERSION);
            assertThat(result.getFailureDetails()).contains("default mappings disabled");
            verifyNoInteractions(defaultGLMappingRepository);
        }

        @Test
        @DisplayName("Should include '(default mappings disabled)' hint in failure message when disabled")
        void shouldIncludeDisabledHintInFailureMessage() {
            // Arrange
            defaultGLMappingProperties.setEnabled(false);
            stubNoPostingRules();

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert
            assertThat(result.getFailureDetails()).contains("(default mappings disabled)");
        }

        @Test
        @DisplayName("Should not include disabled hint when enabled=true but no default mapping found")
        void shouldNotIncludeDisabledHintWhenEnabledButNoMapping() {
            // Arrange
            defaultGLMappingProperties.setEnabled(true);
            stubNoPostingRules();

            when(defaultGLMappingRepository.findActiveDefaultForEvent(anyString(), any()))
                    .thenReturn(Optional.empty());

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.NO_RULE_VERSION);
            assertThat(result.getFailureDetails()).doesNotContain("(default mappings disabled)");
        }
    }

    @Nested
    @DisplayName("allowGlobalDefaults flag")
    class AllowGlobalDefaultsTests {

        @Test
        @DisplayName("Should accept global default mapping when allowGlobalDefaults=true")
        void shouldAcceptGlobalMappingWhenAllowed() {
            // Arrange
            defaultGLMappingProperties.setEnabled(true);
            defaultGLMappingProperties.setAllowGlobalDefaults(true);
            defaultGLMappingProperties.setRequireAmountField(false);
            stubNoPostingRules();

            // Global mapping (organizationId = null)
            DefaultGLMapping globalMapping = createDefaultMapping(null);
            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    "billing.invoicePosted", testOrganizationId))
                    .thenReturn(Optional.of(globalMapping));

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "250.00");

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft()).isNotNull();
            assertThat(result.getEvaluationDetails())
                    .containsEntry("mappingType", "defaultGLMapping");
        }

        @Test
        @DisplayName("Should reject global default mapping when allowGlobalDefaults=false")
        void shouldRejectGlobalMappingWhenNotAllowed() {
            // Arrange
            defaultGLMappingProperties.setEnabled(true);
            defaultGLMappingProperties.setAllowGlobalDefaults(false);
            stubNoPostingRules();

            // Repository returns a global mapping, but the filter in
            // loadDefaultGLMapping will reject it because organizationId is null
            DefaultGLMapping globalMapping = createDefaultMapping(null);
            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    "billing.invoicePosted", testOrganizationId))
                    .thenReturn(Optional.of(globalMapping));

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "250.00");

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.NO_RULE_VERSION);
        }

        @Test
        @DisplayName("Should accept org-specific mapping regardless of allowGlobalDefaults setting")
        void shouldAcceptOrgSpecificMappingWhenGlobalsDisallowed() {
            // Arrange
            defaultGLMappingProperties.setEnabled(true);
            defaultGLMappingProperties.setAllowGlobalDefaults(false);
            defaultGLMappingProperties.setRequireAmountField(false);
            stubNoPostingRules();

            DefaultGLMapping orgMapping = createDefaultMapping(testOrganizationId);
            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    "billing.invoicePosted", testOrganizationId))
                    .thenReturn(Optional.of(orgMapping));

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "750.00");

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft()).isNotNull();
        }
    }

    @Nested
    @DisplayName("requireAmountField flag")
    class RequireAmountFieldTests {

        @Test
        @DisplayName("Should fail when requireAmountField=true and event has no amount")
        void shouldFailWhenAmountRequiredButMissing() {
            // Arrange
            defaultGLMappingProperties.setEnabled(true);
            defaultGLMappingProperties.setAllowGlobalDefaults(true);
            defaultGLMappingProperties.setRequireAmountField(true);
            stubNoPostingRules();

            DefaultGLMapping mapping = createDefaultMapping(testOrganizationId);
            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    "billing.invoicePosted", testOrganizationId))
                    .thenReturn(Optional.of(mapping));

            // Event with empty payload — no amount field
            AccountingEvent event = createEvent("billing.invoicePosted", new HashMap<>());

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert — the impl throws IllegalArgumentException caught as INTERNAL_ERROR
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.INTERNAL_ERROR);
            assertThat(result.getFailureDetails()).contains("payload.amount");
        }

        @Test
        @DisplayName("Should succeed with fallback amount when requireAmountField=false and event has no amount")
        void shouldSucceedWithFallbackAmountWhenNotRequired() {
            // Arrange
            defaultGLMappingProperties.setEnabled(true);
            defaultGLMappingProperties.setAllowGlobalDefaults(true);
            defaultGLMappingProperties.setRequireAmountField(false);
            stubNoPostingRules();

            DefaultGLMapping mapping = createDefaultMapping(testOrganizationId);
            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    "billing.invoicePosted", testOrganizationId))
                    .thenReturn(Optional.of(mapping));

            // Event without amount
            AccountingEvent event = createEvent("billing.invoicePosted", new HashMap<>());

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert — should produce journal entry with fallback amount (0.01)
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft()).isNotNull();
            assertThat(result.getJournalEntryDraft().getLines()).hasSize(2);

            BigDecimal fallbackAmount = new BigDecimal("0.01");
            assertThat(result.getJournalEntryDraft().getLines().get(0).getDebitAmount())
                    .isEqualByComparingTo(fallbackAmount);
            assertThat(result.getJournalEntryDraft().getLines().get(1).getCreditAmount())
                    .isEqualByComparingTo(fallbackAmount);
        }

        @Test
        @DisplayName("Should succeed normally when requireAmountField=true and amount is present")
        void shouldSucceedWhenAmountRequiredAndPresent() {
            // Arrange
            defaultGLMappingProperties.setEnabled(true);
            defaultGLMappingProperties.setAllowGlobalDefaults(true);
            defaultGLMappingProperties.setRequireAmountField(true);
            stubNoPostingRules();

            DefaultGLMapping mapping = createDefaultMapping(testOrganizationId);
            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    "billing.invoicePosted", testOrganizationId))
                    .thenReturn(Optional.of(mapping));

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "1500.00");

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft()).isNotNull();
            assertThat(result.getJournalEntryDraft().getLines()).hasSize(2);

            BigDecimal expectedAmount = new BigDecimal("1500.00");
            assertThat(result.getJournalEntryDraft().getLines().get(0).getDebitAmount())
                    .isEqualByComparingTo(expectedAmount);
            assertThat(result.getJournalEntryDraft().getLines().get(1).getCreditAmount())
                    .isEqualByComparingTo(expectedAmount);
        }

        @Test
        @DisplayName("Should fail when requireAmountField=true and amount is zero")
        void shouldFailWhenAmountRequiredButZero() {
            // Arrange
            defaultGLMappingProperties.setEnabled(true);
            defaultGLMappingProperties.setAllowGlobalDefaults(true);
            defaultGLMappingProperties.setRequireAmountField(true);
            stubNoPostingRules();

            DefaultGLMapping mapping = createDefaultMapping(testOrganizationId);
            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    "billing.invoicePosted", testOrganizationId))
                    .thenReturn(Optional.of(mapping));

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "0");

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.INTERNAL_ERROR);
        }
    }

    @Nested
    @DisplayName("Feature flag combinations")
    class CombinationTests {

        @Test
        @DisplayName("Should not query default mappings at all when enabled=false")
        void shouldNotQueryDefaultMappingsWhenDisabled() {
            // Arrange
            defaultGLMappingProperties.setEnabled(false);
            stubNoPostingRules();

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            // Act
            evaluator.evaluateEvent(event);

            // Assert
            verifyNoInteractions(defaultGLMappingRepository);
        }

        @Test
        @DisplayName("Should produce balanced journal entry from default mapping")
        void shouldProduceBalancedJournalEntryFromDefaultMapping() {
            // Arrange
            defaultGLMappingProperties.setEnabled(true);
            defaultGLMappingProperties.setAllowGlobalDefaults(true);
            defaultGLMappingProperties.setRequireAmountField(false);
            stubNoPostingRules();

            DefaultGLMapping mapping = createDefaultMapping(testOrganizationId);
            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    "billing.invoicePosted", testOrganizationId))
                    .thenReturn(Optional.of(mapping));

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "999.99");

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert — journal entry must be balanced (total debits == total credits)
            assertThat(result.isSuccess()).isTrue();
            var journalEntry = result.getJournalEntryDraft();
            assertThat(journalEntry).isNotNull();

            BigDecimal totalDebits = journalEntry.getLines().stream()
                    .map(line -> line.getDebitAmount() != null ? line.getDebitAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCredits = journalEntry.getLines().stream()
                    .map(line -> line.getCreditAmount() != null ? line.getCreditAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(totalDebits)
                    .isEqualByComparingTo(totalCredits).isEqualByComparingTo(new BigDecimal("999.99"));
        }

        @Test
        @DisplayName("Should set correct GL accounts from default mapping")
        void shouldSetCorrectGLAccountsFromDefaultMapping() {
            // Arrange
            defaultGLMappingProperties.setEnabled(true);
            defaultGLMappingProperties.setAllowGlobalDefaults(true);
            defaultGLMappingProperties.setRequireAmountField(false);
            stubNoPostingRules();

            DefaultGLMapping mapping = createDefaultMapping(testOrganizationId);
            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    "billing.invoicePosted", testOrganizationId))
                    .thenReturn(Optional.of(mapping));

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            var lines = result.getJournalEntryDraft().getLines();
            assertThat(lines.get(0).getGlAccountId()).isEqualTo(testDebitAccountId);
            assertThat(lines.get(1).getGlAccountId()).isEqualTo(testCreditAccountId);
        }

        @Test
        @DisplayName("Should set null posting rule references on default-mapped journal entry")
        void shouldSetNullPostingRuleReferencesOnDefaultMappedEntry() {
            // Arrange
            defaultGLMappingProperties.setEnabled(true);
            defaultGLMappingProperties.setAllowGlobalDefaults(true);
            defaultGLMappingProperties.setRequireAmountField(false);
            stubNoPostingRules();

            DefaultGLMapping mapping = createDefaultMapping(testOrganizationId);
            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    "billing.invoicePosted", testOrganizationId))
                    .thenReturn(Optional.of(mapping));

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert — no posting rule was used, so these should be null
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft().getPostingRuleVersionId()).isNull();
            assertThat(result.getJournalEntryDraft().getPostingRuleSetId()).isNull();
            assertThat(result.getMappingVersionUsed()).isNull();
        }

        @Test
        @DisplayName("Should include defaultMappingId in evaluation details")
        void shouldIncludeDefaultMappingIdInEvaluationDetails() {
            // Arrange
            defaultGLMappingProperties.setEnabled(true);
            defaultGLMappingProperties.setAllowGlobalDefaults(true);
            defaultGLMappingProperties.setRequireAmountField(false);
            stubNoPostingRules();

            DefaultGLMapping mapping = createDefaultMapping(testOrganizationId);
            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    "billing.invoicePosted", testOrganizationId))
                    .thenReturn(Optional.of(mapping));

            AccountingEvent event = createEventWithAmount("billing.invoicePosted", "100.00");

            // Act
            PostingResult result = evaluator.evaluateEvent(event);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails())
                    .containsEntry("defaultMappingId", mapping.getMappingId())
                    .containsEntry("mappingType", "defaultGLMapping")
                    .containsKey("journalEntryLineCount")
                    .containsKey("journalEntryAmount");
        }
    }
}
