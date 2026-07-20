package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.DefaultGLMappingProperties;
import com.positivity.accounting.internal.dto.MappingResolutionTestRequest;
import com.positivity.accounting.internal.dto.MappingResolutionTestResponse;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.PostingRuleSet;
import com.positivity.accounting.internal.entity.PostingRuleVersion;
import com.positivity.accounting.internal.enums.PostingRuleSetState;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.PostingRuleSetRepository;
import com.positivity.accounting.internal.repository.PostingRuleVersionRepository;
import com.positivity.accounting.internal.service.MappingResolutionTestServiceImpl;
import com.positivity.accounting.internal.service.PostingRuleEvaluatorImpl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * Unit tests for the dry-run mapping resolution service (story E3, issue
 * #957).
 *
 * <p>The service under test is wired against the REAL
 * {@link PostingRuleEvaluatorImpl} (with mocked repositories) so the dry-run
 * output is produced by exactly the same machinery as real posting — E1
 * proportional split shares with residual distribution and E2 predicate
 * evaluation included — while proving that no persistence interaction of any
 * kind takes place.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MappingResolutionTestService dry-run resolution (E3)")
class MappingResolutionTestServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String EVENT_TYPE = "billing.invoicePosted";
    private static final LocalDate TX_DATE = LocalDate.of(2026, 1, 15);

    private static final UUID RULE_SET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID VERSION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

    private static final UUID ACCOUNT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ACCOUNT_B = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID ACCOUNT_C = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID ACCOUNT_D = UUID.fromString("00000000-0000-0000-0000-0000000000a4");

    @Mock
    private PostingRuleVersionRepository versionRepository;

    @Mock
    private PostingRuleSetRepository ruleSetRepository;

    @Mock
    private GLMappingResolver glMappingResolver;

    @Mock
    private DefaultGLMappingRepository defaultGLMappingRepository;

    @Mock
    private GLAccountRepository glAccountRepository;

    private MappingResolutionTestServiceImpl service;

    @BeforeEach
    void setUp() {
        DefaultGLMappingProperties properties = new DefaultGLMappingProperties();
        properties.setEnabled(false);

        PostingRuleEvaluatorImpl evaluator = new PostingRuleEvaluatorImpl(
                TEST_CLOCK,
                versionRepository,
                ruleSetRepository,
                glMappingResolver,
                defaultGLMappingRepository,
                properties,
                new ObjectMapper());

        service = new MappingResolutionTestServiceImpl(
                evaluator, versionRepository, glAccountRepository, new ObjectMapper());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubPublishedRules(String rulesDefinition) {
        PostingRuleSet ruleSet = new PostingRuleSet();
        ruleSet.setPostingRuleSetId(RULE_SET_ID);
        ruleSet.setName("Invoice finalization postings");
        ruleSet.setEventType(EVENT_TYPE);

        PostingRuleVersion version = new PostingRuleVersion();
        version.setVersionId(VERSION_ID);
        version.setVersionNumber(3);
        version.setState(PostingRuleSetState.PUBLISHED);
        version.setRulesDefinition(rulesDefinition);
        version.setPostingRuleSet(ruleSet);

        when(ruleSetRepository.findByEventType(EVENT_TYPE)).thenReturn(List.of(ruleSet));
        when(versionRepository.findByPostingRuleSet_PostingRuleSetIdAndState(
                        RULE_SET_ID, PostingRuleSetState.PUBLISHED))
                .thenReturn(List.of(version));
        when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));
    }

    private static GLAccount glAccount(UUID id, String code, String name) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(id);
        account.setAccountCode(code);
        account.setAccountName(name);
        return account;
    }

    private static String line(UUID account, String side, String extraJson) {
        return "{\"glAccountId\":\"" + account + "\",\"side\":\"" + side
                + "\",\"amountField\":\"payload.totalAmount\""
                + (extraJson.isEmpty() ? "" : "," + extraJson) + "}";
    }

    private static String splitLine(UUID account, String side, String factor, String group) {
        return line(account, side, "\"factorPercent\":" + factor + ",\"splitGroup\":\"" + group + "\"");
    }

    private static String rules(String condition, String... lines) {
        return "{\"conditions\":[{\"condition\":\"" + condition + "\",\"lines\":[" + String.join(",", lines) + "]}]}";
    }

    private static MappingResolutionTestRequest request(String eventType, Map<String, Object> payload) {
        return MappingResolutionTestRequest.builder()
                .eventType(eventType)
                .samplePayload(payload)
                .transactionDate(TX_DATE)
                .build();
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Happy path: matched rule with E1 split lines")
    class HappyPath {

        private static final String CONDITION = "payload.channel == 'POS' && payload.totalAmount > 100";

        private MappingResolutionTestResponse resolveHappyPath() {
            stubPublishedRules(rules(
                    CONDITION,
                    splitLine(ACCOUNT_A, "DEBIT", "33.3333", "revenue-split"),
                    splitLine(ACCOUNT_B, "DEBIT", "33.3333", "revenue-split"),
                    splitLine(ACCOUNT_C, "DEBIT", "33.3334", "revenue-split"),
                    line(ACCOUNT_D, "CREDIT", "")));
            when(glAccountRepository.findAllById(any()))
                    .thenReturn(List.of(
                            glAccount(ACCOUNT_A, "4000", "Sales Revenue A"),
                            glAccount(ACCOUNT_B, "4001", "Sales Revenue B"),
                            glAccount(ACCOUNT_C, "4002", "Sales Revenue C"),
                            glAccount(ACCOUNT_D, "1200", "Accounts Receivable")));

            Map<String, Object> payload = new HashMap<>();
            payload.put("channel", "POS");
            payload.put("totalAmount", "125.00");
            return service.resolveTest(request(EVENT_TYPE, payload));
        }

        @Test
        @DisplayName("matches the published rule and reports its identity")
        void matchesPublishedRule() {
            MappingResolutionTestResponse response = resolveHappyPath();

            assertThat(response.isMatched()).isTrue();
            assertThat(response.getNoMatchReason()).isNull();
            assertThat(response.getMatchedRule()).isNotNull();
            assertThat(response.getMatchedRule().getPostingRuleSetId()).isEqualTo(RULE_SET_ID);
            assertThat(response.getMatchedRule().getRuleSetName()).isEqualTo("Invoice finalization postings");
            assertThat(response.getMatchedRule().getRuleVersionId()).isEqualTo(VERSION_ID);
            assertThat(response.getMatchedRule().getVersionNumber()).isEqualTo(3);
        }

        @Test
        @DisplayName("reports mapping type, key, and keys evaluated")
        void reportsMappingDetails() {
            MappingResolutionTestResponse response = resolveHappyPath();

            assertThat(response.getMatchedMapping()).isNotNull();
            assertThat(response.getMatchedMapping().getMappingType()).isEqualTo("exact");
            assertThat(response.getMatchedMapping().getMappingKey()).isEqualTo(CONDITION);
            assertThat(response.getMatchedMapping().getKeysEvaluated()).containsExactly(CONDITION);
        }

        @Test
        @DisplayName("resolves E1 split shares that sum exactly to the source amount")
        void resolvesSplitSharesExactly() {
            MappingResolutionTestResponse response = resolveHappyPath();

            List<MappingResolutionTestResponse.ResolvedLine> lines = response.getResolvedLines();
            assertThat(lines).hasSize(4);

            // 125.00 x 33.3333% = 41.666625 → 41.67 (HALF_UP); x3 rounded sum
            // = 41.67+41.67+41.67 = 125.01 → residual -0.01 goes to the line
            // with the largest raw share (the 33.3334 line, index 2)
            assertThat(lines.get(0).getDebitAmount()).isEqualByComparingTo("41.67");
            assertThat(lines.get(1).getDebitAmount()).isEqualByComparingTo("41.67");
            assertThat(lines.get(2).getDebitAmount()).isEqualByComparingTo("41.66");
            assertThat(lines.get(3).getCreditAmount()).isEqualByComparingTo("125.00");

            BigDecimal debitSum = lines.subList(0, 3).stream()
                    .map(MappingResolutionTestResponse.ResolvedLine::getDebitAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(debitSum).isEqualByComparingTo("125.00");
        }

        @Test
        @DisplayName("annotates split lines with splitGroup and factorPercent")
        void annotatesSplitMetadata() {
            MappingResolutionTestResponse response = resolveHappyPath();

            List<MappingResolutionTestResponse.ResolvedLine> lines = response.getResolvedLines();
            assertThat(lines.get(0).getSplitGroup()).isEqualTo("revenue-split");
            assertThat(lines.get(0).getFactorPercent()).isEqualByComparingTo("33.3333");
            assertThat(lines.get(2).getSplitGroup()).isEqualTo("revenue-split");
            assertThat(lines.get(2).getFactorPercent()).isEqualByComparingTo("33.3334");
            assertThat(lines.get(3).getSplitGroup()).isNull();
            assertThat(lines.get(3).getFactorPercent()).isNull();
        }

        @Test
        @DisplayName("enriches lines with GL account code and name")
        void enrichesAccountCodeAndName() {
            MappingResolutionTestResponse response = resolveHappyPath();

            List<MappingResolutionTestResponse.ResolvedLine> lines = response.getResolvedLines();
            assertThat(lines.get(0).getGlAccountId()).isEqualTo(ACCOUNT_A);
            assertThat(lines.get(0).getAccountCode()).isEqualTo("4000");
            assertThat(lines.get(0).getAccountName()).isEqualTo("Sales Revenue A");
            assertThat(lines.get(3).getAccountCode()).isEqualTo("1200");
            assertThat(lines.get(3).getAccountName()).isEqualTo("Accounts Receivable");
        }

        @Test
        @DisplayName("reports the matched predicate evaluation")
        void reportsPredicateEvaluations() {
            MappingResolutionTestResponse response = resolveHappyPath();

            assertThat(response.getPredicateEvaluations()).hasSize(1);
            MappingResolutionTestResponse.PredicateEvaluation eval =
                    response.getPredicateEvaluations().get(0);
            assertThat(eval.getPredicate()).isEqualTo(CONDITION);
            assertThat(eval.isMatched()).isTrue();
        }

        @Test
        @DisplayName("performs zero persistence interactions")
        void performsZeroPersistence() {
            resolveHappyPath();

            verify(versionRepository, never()).save(any());
            verify(versionRepository, never()).saveAll(any());
            verify(versionRepository, never()).delete(any());
            verify(ruleSetRepository, never()).save(any());
            verify(ruleSetRepository, never()).saveAll(any());
            verify(defaultGLMappingRepository, never()).save(any());
            verify(defaultGLMappingRepository, never()).saveAll(any());
            verify(glAccountRepository, never()).save(any());
            verify(glAccountRepository, never()).saveAll(any());
            // Direct glAccountId rules never touch the mapping resolver
            verifyNoInteractions(glMappingResolver);
        }
    }

    @Nested
    @DisplayName("No-match outcomes")
    class NoMatch {

        @Test
        @DisplayName("predicate non-match returns matched=false with failing clause detail")
        void predicateNonMatch() {
            stubPublishedRules(rules(
                    "payload.channel == 'POS' && payload.totalAmount > 100",
                    line(ACCOUNT_A, "DEBIT", ""),
                    line(ACCOUNT_D, "CREDIT", "")));

            Map<String, Object> payload = new HashMap<>();
            payload.put("channel", "WEB");
            payload.put("totalAmount", "125.00");

            MappingResolutionTestResponse response = service.resolveTest(request(EVENT_TYPE, payload));

            assertThat(response.isMatched()).isFalse();
            assertThat(response.getNoMatchReason()).isEqualTo("UNMAPPED_EVENT_TYPE");
            assertThat(response.getNoMatchDetail()).isNotBlank();
            assertThat(response.getMatchedRule()).isNull();
            assertThat(response.getResolvedLines()).isNull();

            assertThat(response.getPredicateEvaluations()).hasSize(1);
            MappingResolutionTestResponse.PredicateEvaluation eval =
                    response.getPredicateEvaluations().get(0);
            assertThat(eval.isMatched()).isFalse();
            assertThat(eval.getDetail()).contains("payload.channel == 'POS'");
        }

        @Test
        @DisplayName("unknown event type returns matched=false with NO_RULE_VERSION reason")
        void unknownEventType() {
            when(ruleSetRepository.findByEventType("unknown.event")).thenReturn(List.of());

            MappingResolutionTestResponse response =
                    service.resolveTest(request("unknown.event", Map.of("totalAmount", "10.00")));

            assertThat(response.isMatched()).isFalse();
            assertThat(response.getNoMatchReason()).isEqualTo("NO_RULE_VERSION");
            assertThat(response.getNoMatchDetail()).contains("unknown.event");
            assertThat(response.getMatchedRule()).isNull();
            assertThat(response.getMatchedMapping()).isNull();
            assertThat(response.getResolvedLines()).isNull();
        }

        @Test
        @DisplayName("no-match performs zero persistence interactions")
        void noMatchPerformsZeroPersistence() {
            when(ruleSetRepository.findByEventType("unknown.event")).thenReturn(List.of());

            service.resolveTest(request("unknown.event", Map.of()));

            verify(versionRepository, never()).save(any());
            verify(ruleSetRepository, never()).save(any());
            verify(defaultGLMappingRepository, never()).save(any());
            verify(glAccountRepository, never()).save(any());
            verifyNoInteractions(glMappingResolver);
        }
    }

    @Nested
    @DisplayName("Caller errors")
    class CallerErrors {

        @Test
        @DisplayName("non-numeric amount field in sample payload throws IllegalArgumentException")
        void nonNumericAmountThrows() {
            stubPublishedRules(rules(
                    "eventType == '" + EVENT_TYPE + "'", line(ACCOUNT_A, "DEBIT", ""), line(ACCOUNT_D, "CREDIT", "")));

            Map<String, Object> payload = new HashMap<>();
            payload.put("totalAmount", "not-a-number");

            assertThatThrownBy(() -> service.resolveTest(request(EVENT_TYPE, payload)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("totalAmount");
        }

        @Test
        @DisplayName("non-UUID organizationId in sample payload throws IllegalArgumentException")
        void badOrganizationIdThrows() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("organizationId", "not-a-uuid");

            assertThatThrownBy(() -> service.resolveTest(request(EVENT_TYPE, payload)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("organizationId");
        }
    }

    @Nested
    @DisplayName("Missing amount field mirrors the real engine")
    class MissingAmount {

        @Test
        @DisplayName("absent amount field resolves to zero amounts, matching real posting")
        void absentAmountFieldMatchesWithZeroAmounts() {
            stubPublishedRules(rules(
                    "eventType == '" + EVENT_TYPE + "'", line(ACCOUNT_A, "DEBIT", ""), line(ACCOUNT_D, "CREDIT", "")));
            when(glAccountRepository.findAllById(any()))
                    .thenReturn(List.of(
                            glAccount(ACCOUNT_A, "4000", "Sales Revenue A"),
                            glAccount(ACCOUNT_D, "1200", "Accounts Receivable")));

            MappingResolutionTestResponse response = service.resolveTest(request(EVENT_TYPE, Map.of("channel", "POS")));

            assertThat(response.isMatched()).isTrue();
            assertThat(response.getResolvedLines()).hasSize(2);
            assertThat(response.getResolvedLines().get(0).getDebitAmount()).isEqualByComparingTo("0");
            assertThat(response.getResolvedLines().get(1).getCreditAmount()).isEqualByComparingTo("0");
        }
    }
}
