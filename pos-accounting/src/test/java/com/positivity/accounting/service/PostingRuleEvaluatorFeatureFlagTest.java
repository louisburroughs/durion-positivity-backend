package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
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

import com.positivity.accounting.internal.config.DefaultGLMappingProperties;
import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.DefaultGLMapping;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.PostingRuleSetRepository;
import com.positivity.accounting.internal.repository.PostingRuleVersionRepository;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Feature flag tests for PostingRuleEvaluator with DefaultGLMapping fallback.
 * 
 * Tests various configurations of DefaultGLMappingProperties:
 * - enabled: master switch for default mapping fallback
 * - allowGlobalDefaults: whether to fallback to global (org=null) defaults
 * - requireAmountField: whether to enforce payload.amount presence
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostingRuleEvaluator Feature Flag Tests")
class PostingRuleEvaluatorFeatureFlagTest {

    @Mock
    private PostingRuleVersionRepository versionRepository;

    @Mock
    private PostingRuleSetRepository ruleSetRepository;

    @Mock
    private GLMappingResolver glMappingResolver;

    @Mock
    private DefaultGLMappingRepository defaultGLMappingRepository;

    @Mock
    private DefaultGLMappingProperties featureFlags;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private PostingRuleEvaluatorImpl evaluator;

    private AccountingEvent testEvent;
    private UUID organizationId;
    private UUID debitAccountId;
    private UUID creditAccountId;

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        debitAccountId = UUID.randomUUID();
        creditAccountId = UUID.randomUUID();

        testEvent = new AccountingEvent();
        testEvent.setEventId(UUID.randomUUID());
        testEvent.setEventType("billing.invoicePosted");
        testEvent.setOrganizationId(organizationId);
        testEvent.setTransactionDate(LocalDate.now());
        testEvent.setRevisionNumber(1);

        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("amount", "100.00");
        payload.put("customerId", UUID.randomUUID().toString());
        testEvent.setPayload(payload);
    }

    @Nested
    @DisplayName("Feature: enabled flag")
    class EnabledFlagTests {

        @Test
        @DisplayName("When enabled=false and no posting rule exists, should fail without checking default mappings")
        void whenDisabledAndNoPostingRule_shouldFailImmediately() {
            // Arrange
            when(featureFlags.isEnabled()).thenReturn(false);
            when(versionRepository.findLatestPublishedForEventTypeAndDate(
                    eq("billing.invoicePosted"), eq(organizationId), any())).thenReturn(Optional.empty());

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.NO_RULE_VERSION);
            assertThat(result.getMessage()).contains("(default mappings disabled)");
            verify(defaultGLMappingRepository, never()).findActiveDefaultForEvent(anyString(), any());
        }

        @Test
        @DisplayName("When enabled=true and no posting rule exists, should attempt default mapping fallback")
        void whenEnabledAndNoPostingRule_shouldTryDefaultMappings() {
            // Arrange
            when(featureFlags.isEnabled()).thenReturn(true);
            when(featureFlags.isAllowGlobalDefaults()).thenReturn(true);
            when(featureFlags.isRequireAmountField()).thenReturn(true);
            when(versionRepository.findLatestPublishedForEventTypeAndDate(
                    eq("billing.invoicePosted"), eq(organizationId), any())).thenReturn(Optional.empty());
            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    eq("billing.invoicePosted"), eq(organizationId))).thenReturn(Optional.empty());

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            verify(defaultGLMappingRepository).findActiveDefaultForEvent("billing.invoicePosted", organizationId);
        }
    }

    @Nested
    @DisplayName("Feature: allowGlobalDefaults flag")
    class AllowGlobalDefaultsTests {

        @Test
        @DisplayName("When allowGlobalDefaults=true, should accept global default mappings")
        void whenAllowGlobalDefaults_shouldAcceptGlobalMapping() {
            // Arrange
            when(featureFlags.isEnabled()).thenReturn(true);
            when(featureFlags.isAllowGlobalDefaults()).thenReturn(true);
            when(featureFlags.isRequireAmountField()).thenReturn(true);
            when(versionRepository.findLatestPublishedForEventTypeAndDate(
                    eq("billing.invoicePosted"), eq(organizationId), any())).thenReturn(Optional.empty());

            DefaultGLMapping globalMapping = new DefaultGLMapping();
            globalMapping.setMappingId(UUID.randomUUID());
            globalMapping.setEventType("billing.invoicePosted");
            globalMapping.setOrganizationId(null); // Global mapping
            globalMapping.setDebitAccountId(debitAccountId);
            globalMapping.setCreditAccountId(creditAccountId);
            globalMapping.setActive(true);

            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    eq("billing.invoicePosted"), eq(organizationId)))
                    .thenReturn(Optional.of(globalMapping));

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails()).containsEntry("mappingType", "defaultGLMapping");
        }

        @Test
        @DisplayName("When allowGlobalDefaults=false and only global mapping exists, should reject it")
        void whenDisallowGlobalDefaults_shouldRejectGlobalMapping() {
            // Arrange
            when(featureFlags.isEnabled()).thenReturn(true);
            when(featureFlags.isAllowGlobalDefaults()).thenReturn(false);
            when(versionRepository.findLatestPublishedForEventTypeAndDate(
                    eq("billing.invoicePosted"), eq(organizationId), any())).thenReturn(Optional.empty());

            DefaultGLMapping globalMapping = new DefaultGLMapping();
            globalMapping.setMappingId(UUID.randomUUID());
            globalMapping.setEventType("billing.invoicePosted");
            globalMapping.setOrganizationId(null); // Global mapping
            globalMapping.setDebitAccountId(debitAccountId);
            globalMapping.setCreditAccountId(creditAccountId);
            globalMapping.setActive(true);

            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    eq("billing.invoicePosted"), eq(organizationId)))
                    .thenReturn(Optional.of(globalMapping));

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.NO_RULE_VERSION);
        }

        @Test
        @DisplayName("When allowGlobalDefaults=false and org-specific mapping exists, should accept it")
        void whenDisallowGlobalDefaults_shouldAcceptOrgSpecificMapping() {
            // Arrange
            when(featureFlags.isEnabled()).thenReturn(true);
            when(featureFlags.isAllowGlobalDefaults()).thenReturn(false);
            when(featureFlags.isRequireAmountField()).thenReturn(true);
            when(versionRepository.findLatestPublishedForEventTypeAndDate(
                    eq("billing.invoicePosted"), eq(organizationId), any())).thenReturn(Optional.empty());

            DefaultGLMapping orgMapping = new DefaultGLMapping();
            orgMapping.setMappingId(UUID.randomUUID());
            orgMapping.setEventType("billing.invoicePosted");
            orgMapping.setOrganizationId(organizationId); // Org-specific
            orgMapping.setDebitAccountId(debitAccountId);
            orgMapping.setCreditAccountId(creditAccountId);
            orgMapping.setActive(true);

            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    eq("billing.invoicePosted"), eq(organizationId)))
                    .thenReturn(Optional.of(orgMapping));

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails()).containsEntry("mappingType", "defaultGLMapping");
        }
    }

    @Nested
    @DisplayName("Feature: requireAmountField flag")
    class RequireAmountFieldTests {

        @Test
        @DisplayName("When requireAmountField=true and amount is missing, should throw exception")
        void whenRequireAmountAndMissingAmount_shouldFail() {
            // Arrange
            when(featureFlags.isEnabled()).thenReturn(true);
            when(featureFlags.isAllowGlobalDefaults()).thenReturn(true);
            when(featureFlags.isRequireAmountField()).thenReturn(true);
            when(versionRepository.findLatestPublishedForEventTypeAndDate(
                    eq("billing.invoicePosted"), eq(organizationId), any())).thenReturn(Optional.empty());

            DefaultGLMapping mapping = new DefaultGLMapping();
            mapping.setMappingId(UUID.randomUUID());
            mapping.setEventType("billing.invoicePosted");
            mapping.setOrganizationId(organizationId);
            mapping.setDebitAccountId(debitAccountId);
            mapping.setCreditAccountId(creditAccountId);
            mapping.setActive(true);

            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    eq("billing.invoicePosted"), eq(organizationId)))
                    .thenReturn(Optional.of(mapping));

            // Set event payload with missing/zero amount
            ObjectNode emptyPayload = new ObjectMapper().createObjectNode();
            testEvent.setPayload(emptyPayload);

            // Act & Assert
            assertThatThrownBy(() -> evaluator.evaluateEvent(testEvent, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("payload.amount field")
                    .hasMessageContaining("required when using default GL mappings");
        }

        @Test
        @DisplayName("When requireAmountField=false and amount is missing, should use fallback amount")
        void whenNotRequireAmountAndMissingAmount_shouldUseFallback() {
            // Arrange
            when(featureFlags.isEnabled()).thenReturn(true);
            when(featureFlags.isAllowGlobalDefaults()).thenReturn(true);
            when(featureFlags.isRequireAmountField()).thenReturn(false);
            when(versionRepository.findLatestPublishedForEventTypeAndDate(
                    eq("billing.invoicePosted"), eq(organizationId), any())).thenReturn(Optional.empty());

            DefaultGLMapping mapping = new DefaultGLMapping();
            mapping.setMappingId(UUID.randomUUID());
            mapping.setEventType("billing.invoicePosted");
            mapping.setOrganizationId(organizationId);
            mapping.setDebitAccountId(debitAccountId);
            mapping.setCreditAccountId(creditAccountId);
            mapping.setActive(true);

            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    eq("billing.invoicePosted"), eq(organizationId)))
                    .thenReturn(Optional.of(mapping));

            // Set event payload with missing/zero amount
            ObjectNode emptyPayload = new ObjectMapper().createObjectNode();
            testEvent.setPayload(emptyPayload);

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntry()).isNotNull();
            // Should use fallback amount (0.01)
            assertThat(result.getJournalEntry().getTotalDebits())
                    .isEqualByComparingTo(new BigDecimal("0.01"));
        }

        @Test
        @DisplayName("When requireAmountField=true and amount is valid, should proceed normally")
        void whenRequireAmountAndValidAmount_shouldSucceed() {
            // Arrange
            when(featureFlags.isEnabled()).thenReturn(true);
            when(featureFlags.isAllowGlobalDefaults()).thenReturn(true);
            when(featureFlags.isRequireAmountField()).thenReturn(true);
            when(versionRepository.findLatestPublishedForEventTypeAndDate(
                    eq("billing.invoicePosted"), eq(organizationId), any())).thenReturn(Optional.empty());

            DefaultGLMapping mapping = new DefaultGLMapping();
            mapping.setMappingId(UUID.randomUUID());
            mapping.setEventType("billing.invoicePosted");
            mapping.setOrganizationId(organizationId);
            mapping.setDebitAccountId(debitAccountId);
            mapping.setCreditAccountId(creditAccountId);
            mapping.setActive(true);

            when(defaultGLMappingRepository.findActiveDefaultForEvent(
                    eq("billing.invoicePosted"), eq(organizationId)))
                    .thenReturn(Optional.of(mapping));

            // Act
            PostingResult result = evaluator.evaluateEvent(testEvent, null);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntry()).isNotNull();
            assertThat(result.getJournalEntry().getTotalDebits())
                    .isEqualByComparingTo(new BigDecimal("100.00"));
        }
    }
}
