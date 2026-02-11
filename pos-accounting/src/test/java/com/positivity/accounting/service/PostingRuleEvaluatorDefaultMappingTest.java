package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.DefaultGLMapping;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.PostingRuleSet;
import com.positivity.accounting.internal.entity.PostingRuleVersion;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.enums.PostingRuleSetState;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.PostingRuleSetRepository;
import com.positivity.accounting.internal.repository.PostingRuleVersionRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for PostingRuleEvaluatorImpl default mapping fallback logic
 * 
 * Tests the fallback to default GL mappings when no posting rules exist.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostingRuleEvaluator Default Mapping Fallback Tests")
class PostingRuleEvaluatorDefaultMappingTest {

    @Mock
    private PostingRuleVersionRepository versionRepository;

    @Mock
    private PostingRuleSetRepository ruleSetRepository;

    @Mock
    private GLMappingResolver glMappingResolver;

    @Mock
    private DefaultGLMappingRepository defaultGLMappingRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private PostingRuleEvaluatorImpl evaluator;

    private UUID organizationId;
    private UUID debitAccountId;
    private UUID creditAccountId;
    private AccountingEvent testEvent;
    private DefaultGLMapping testDefaultMapping;

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        debitAccountId = UUID.randomUUID();
        creditAccountId = UUID.randomUUID();

        testEvent = new AccountingEvent();
        testEvent.setEventId(UUID.randomUUID());
        testEvent.setOrganizationId(organizationId);
        testEvent.setEventType("billing.invoicePosted");
        testEvent.setTransactionDate(LocalDateTime.now());
        testEvent.setPayload(Map.of("amount", new BigDecimal("500.00")));

        testDefaultMapping = new DefaultGLMapping();
        testDefaultMapping.setMappingId(UUID.randomUUID());
        testDefaultMapping.setEventType("billing.invoicePosted");
        testDefaultMapping.setOrganizationId(organizationId);
        testDefaultMapping.setDebitAccountId(debitAccountId);
        testDefaultMapping.setCreditAccountId(creditAccountId);
        testDefaultMapping.setDescription("Default AR/Revenue mapping");
        testDefaultMapping.setActive(true);
    }

    @Nested
    @DisplayName("Fallback to Default Mapping Tests")
    class FallbackToDefaultMappingTests {

        @Test
        @DisplayName("Should use organization-specific default mapping when no posting rule exists")
        void shouldUseOrgSpecificDefaultMappingWhenNoPostingRule() {
            // Arrange
            when(ruleSetRepository.findByEventType("billing.invoicePosted")).thenReturn(List.of());
            when(defaultGLMappingRepository.findActiveDefaultForEvent("billing.invoicePosted", organizationId))
                    .thenReturn(Optional.of(testDefaultMapping));

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntry()).isNotNull();
            assertThat(result.getJournalEntry().getStatus()).isEqualTo(JournalEntryStatus.DRAFT);
            assertThat(result.getJournalEntry().getLines()).hasSize(2);
            assertThat(result.getJournalEntry().getTotalDebits()).isEqualByComparingTo("500.00");
            assertThat(result.getJournalEntry().getTotalCredits()).isEqualByComparingTo("500.00");

            verify(defaultGLMappingRepository).findActiveDefaultForEvent("billing.invoicePosted", organizationId);
        }

        @Test
        @DisplayName("Should use global default mapping when no org-specific default exists")
        void shouldUseGlobalDefaultMappingWhenNoOrgSpecificDefault() {
            // Arrange
            DefaultGLMapping globalMapping = new DefaultGLMapping();
            globalMapping.setMappingId(UUID.randomUUID());
            globalMapping.setEventType("billing.invoicePosted");
            globalMapping.setOrganizationId(null); // Global default
            globalMapping.setDebitAccountId(debitAccountId);
            globalMapping.setCreditAccountId(creditAccountId);
            globalMapping.setActive(true);

            when(ruleSetRepository.findByEventType("billing.invoicePosted")).thenReturn(List.of());
            when(defaultGLMappingRepository.findActiveDefaultForEvent("billing.invoicePosted", organizationId))
                    .thenReturn(Optional.of(globalMapping));

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntry()).isNotNull();
            assertThat(result.getEvaluationDetails()).containsEntry("mappingType", "defaultGLMapping");
        }

        @Test
        @DisplayName("Should fail when no posting rule and no default mapping exists")
        void shouldFailWhenNoPostingRuleAndNoDefaultMapping() {
            // Arrange
            when(ruleSetRepository.findByEventType("billing.invoicePosted")).thenReturn(List.of());
            when(defaultGLMappingRepository.findActiveDefaultForEvent("billing.invoicePosted", organizationId))
                    .thenReturn(Optional.empty());

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.NO_RULE_VERSION);
            assertThat(result.getFailureMessage()).contains("No published rule version or default GL mapping found");

            verify(defaultGLMappingRepository).findActiveDefaultForEvent("billing.invoicePosted", organizationId);
        }

        @Test
        @DisplayName("Should prefer posting rule version over default mapping")
        void shouldPreferPostingRuleVersionOverDefaultMapping() {
            // Arrange
            PostingRuleSet ruleSet = new PostingRuleSet();
            ruleSet.setPostingRuleSetId(UUID.randomUUID());
            ruleSet.setEventType("billing.invoicePosted");

            PostingRuleVersion ruleVersion = new PostingRuleVersion();
            ruleVersion.setVersionId(UUID.randomUUID());
            ruleVersion.setPostingRuleSet(ruleSet);
            ruleVersion.setState(PostingRuleSetState.PUBLISHED);
            ruleVersion.setRulesDefinition("{}"); // Empty rules - will fail

            when(ruleSetRepository.findByEventType("billing.invoicePosted")).thenReturn(List.of(ruleSet));
            when(versionRepository.findByPostingRuleSet_PostingRuleSetIdAndState(
                    ruleSet.getPostingRuleSetId(), PostingRuleSetState.PUBLISHED))
                    .thenReturn(List.of(ruleVersion));
            when(objectMapper.readTree("{}")).thenReturn(null);

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isIn(
                    PostingFailureReason.UNMAPPED_EVENT_TYPE,
                    PostingFailureReason.INTERNAL_ERROR);

            // Should NOT fall back to default mapping when posting rule exists
            verify(defaultGLMappingRepository, never()).findActiveDefaultForEvent(any(), any());
        }
    }

    @Nested
    @DisplayName("Default Mapping Journal Entry Generation Tests")
    class DefaultMappingJournalEntryGenerationTests {

        @Test
        @DisplayName("Should generate balanced journal entry from default mapping")
        void shouldGenerateBalancedJournalEntryFromDefaultMapping() {
            // Arrange
            when(ruleSetRepository.findByEventType("billing.invoicePosted")).thenReturn(List.of());
            when(defaultGLMappingRepository.findActiveDefaultForEvent("billing.invoicePosted", organizationId))
                    .thenReturn(Optional.of(testDefaultMapping));

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isTrue();

            JournalEntry entry = result.getJournalEntry();
            assertThat(entry.getLines()).hasSize(2);
            assertThat(entry.getLines().get(0).getLineNumber()).isEqualTo(1);
            assertThat(entry.getLines().get(1).getLineNumber()).isEqualTo(2);

            // Verify balanced
            assertThat(entry.getTotalDebits()).isEqualByComparingTo(entry.getTotalCredits());
        }

        @Test
        @DisplayName("Should handle zero amount in event by using fallback amount")
        void shouldHandleZeroAmountInEventByUsingFallbackAmount() {
            // Arrange
            testEvent.setPayload(Map.of("amount", BigDecimal.ZERO));
            when(ruleSetRepository.findByEventType("billing.invoicePosted")).thenReturn(List.of());
            when(defaultGLMappingRepository.findActiveDefaultForEvent("billing.invoicePosted", organizationId))
                    .thenReturn(Optional.of(testDefaultMapping));

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntry()).isNotNull();
            // Should use fallback amount (0.01) for traceability
            assertThat(result.getJournalEntry().getTotalDebits()).isGreaterThan(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Should handle missing amount field in event payload")
        void shouldHandleMissingAmountFieldInEventPayload() {
            // Arrange
            testEvent.setPayload(Map.of("invoiceId", "INV-123")); // No amount
            when(ruleSetRepository.findByEventType("billing.invoicePosted")).thenReturn(List.of());
            when(defaultGLMappingRepository.findActiveDefaultForEvent("billing.invoicePosted", organizationId))
                    .thenReturn(Optional.of(testDefaultMapping));

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntry()).isNotNull();
            // Should use fallback amount
            assertThat(result.getJournalEntry().getTotalDebits()).isGreaterThan(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("Event Validation Tests")
    class EventValidationTests {

        @Test
        @DisplayName("Should fail when event missing organizationId")
        void shouldFailWhenEventMissingOrganizationId() {
            // Arrange
            testEvent.setOrganizationId(null);

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.VALIDATION_ERROR);
            assertThat(result.getFailureMessage()).contains("organizationId");

            // Should not attempt to load default mapping
            verify(defaultGLMappingRepository, never()).findActiveDefaultForEvent(any(), any());
        }

        @Test
        @DisplayName("Should fail when event missing transactionDate")
        void shouldFailWhenEventMissingTransactionDate() {
            // Arrange
            testEvent.setTransactionDate(null);

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.VALIDATION_ERROR);
            assertThat(result.getFailureMessage()).contains("transactionDate");
        }

        @Test
        @DisplayName("Should fail when event missing eventType")
        void shouldFailWhenEventMissingEventType() {
            // Arrange
            testEvent.setEventType(null);

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.VALIDATION_ERROR);
            assertThat(result.getFailureMessage()).contains("eventType");
        }
    }
}
