package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.MappingResolutionTestRequest;
import com.positivity.accounting.internal.dto.MappingResolutionTestResponse;
import com.positivity.accounting.internal.dto.MappingResolutionTestResponse.MatchedMapping;
import com.positivity.accounting.internal.dto.MappingResolutionTestResponse.MatchedRule;
import com.positivity.accounting.internal.dto.MappingResolutionTestResponse.PredicateEvaluation;
import com.positivity.accounting.internal.dto.MappingResolutionTestResponse.ResolvedLine;
import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.PostingRuleVersion;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.PostingRuleVersionRepository;
import com.positivity.accounting.service.MappingResolutionTestService;
import com.positivity.accounting.service.PostingRuleEvaluator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dry-run posting rule / mapping resolution (story E3, issue #957).
 *
 * <p>Reuses the production Wave-2 machinery — {@link PostingRuleEvaluator}
 * (E1 split lines, E2 predicates) via a transient, never-saved
 * {@link AccountingEvent} — so the dry-run result is byte-identical to what a
 * real event would post. This service performs <strong>zero persistence</strong>:
 * it builds no accounting event, journal entry, outbox record, or idempotency
 * key; all repository access is read-only lookup (rule version + GL account
 * enrichment). The whole flow runs in a {@code readOnly} transaction.
 *
 * <p>The evaluator produces the authoritative matched rule, resolved line
 * amounts, and mapping metadata. This service additionally re-reads the
 * matched rule version's {@code rulesDefinition} to enrich each line with its
 * E1 {@code splitGroup}/{@code factorPercent} and to surface per-predicate
 * (E2) evaluation outcomes — none of which the evaluator's
 * {@link PostingResult} carries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MappingResolutionTestServiceImpl implements MappingResolutionTestService {

    /**
     * Placeholder organization used only to satisfy the evaluator's
     * non-null guard. Rule-version selection is by event type, so the value
     * does not influence resolution.
     */
    private static final UUID DRY_RUN_ORGANIZATION_ID = new UUID(0L, 0L);

    private final PostingRuleEvaluator postingRuleEvaluator;
    private final PostingRuleVersionRepository versionRepository;
    private final GLAccountRepository glAccountRepository;
    private final ObjectMapper objectMapper;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public MappingResolutionTestResponse resolveTest(@NonNull MappingResolutionTestRequest request) {
        if (request.getEventType() == null || request.getEventType().isBlank()) {
            throw new IllegalArgumentException("Event type is required");
        }
        if (request.getTransactionDate() == null) {
            throw new IllegalArgumentException("Transaction date is required");
        }

        AccountingEvent event = buildTransientEvent(request);

        // Authoritative evaluation via the production engine (no persistence).
        PostingResult result = postingRuleEvaluator.evaluateEvent(event, null);

        if (result.isSuccess()) {
            return buildMatchedResponse(request, event, result);
        }
        return buildNoMatchResponse(request, event, result);
    }

    /**
     * Builds a transient (never-saved) accounting event from the request.
     * The sample payload's {@code organizationId}, when present, must be a
     * valid UUID; a malformed value is a caller error.
     */
    private AccountingEvent buildTransientEvent(MappingResolutionTestRequest request) {
        Map<String, Object> payload =
                request.getSamplePayload() != null ? request.getSamplePayload() : Collections.emptyMap();

        AccountingEvent event = new AccountingEvent();
        event.setEventType(request.getEventType());
        event.setOrganizationId(resolveOrganizationId(payload));
        event.setTransactionDate(request.getTransactionDate().atStartOfDay());
        event.setPayload(payload);
        event.setSourceSystem("DRY_RUN");
        return event;
    }

    private UUID resolveOrganizationId(Map<String, Object> payload) {
        Object raw = payload.get("organizationId");
        if (raw == null) {
            return DRY_RUN_ORGANIZATION_ID;
        }
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Sample payload field 'organizationId' value '" + raw + "' is not a valid UUID", e);
        }
    }

    // ── Matched path ──────────────────────────────────────────────────────────

    private MappingResolutionTestResponse buildMatchedResponse(
            MappingResolutionTestRequest request, AccountingEvent event, PostingResult result) {
        JournalEntry draft = result.getJournalEntryDraft();
        if (draft == null) {
            // Defensive: success without a draft should not happen.
            return MappingResolutionTestResponse.builder()
                    .matched(false)
                    .noMatchReason("INTERNAL_ERROR")
                    .noMatchDetail("Evaluation succeeded but produced no journal entry draft")
                    .build();
        }

        UUID versionId = result.getMappingVersionUsed();
        PostingRuleVersion version =
                versionId != null ? versionRepository.findById(versionId).orElse(null) : null;

        // Re-read the matched rule condition for E1 split metadata and E2
        // predicate outcomes; the evaluator's result does not carry these.
        ConditionScan scan = scanConditions(version, event);

        // A referenced amount field that is present but non-numeric is a
        // caller error (the engine silently coerces it to zero); surface it
        // as a 400 rather than returning a misleading zero-amount result.
        if (scan.matchedLineSpecs != null) {
            assertAmountFieldsInterpretable(scan.matchedLineSpecs, event.getPayload());
        }

        List<ResolvedLine> resolvedLines = buildResolvedLines(draft.getLines(), scan.matchedLineSpecs);

        MatchedRule matchedRule = null;
        if (version != null) {
            matchedRule = MatchedRule.builder()
                    .postingRuleSetId(draft.getPostingRuleSetId())
                    .ruleSetName(
                            version.getPostingRuleSet() != null
                                    ? version.getPostingRuleSet().getName()
                                    : null)
                    .ruleVersionId(version.getVersionId())
                    .versionNumber(version.getVersionNumber())
                    .build();
        }

        MatchedMapping matchedMapping = MatchedMapping.builder()
                .mappingType(asString(result.getEvaluationDetails(), "mappingType"))
                .mappingKey(asString(result.getEvaluationDetails(), "mappingKey"))
                .keysEvaluated(scan.keysEvaluated.isEmpty() ? null : List.copyOf(scan.keysEvaluated))
                .build();

        return MappingResolutionTestResponse.builder()
                .matched(true)
                .matchedRule(matchedRule)
                .matchedMapping(matchedMapping)
                .resolvedLines(resolvedLines)
                .predicateEvaluations(scan.predicateEvaluations.isEmpty() ? null : scan.predicateEvaluations)
                .build();
    }

    private List<ResolvedLine> buildResolvedLines(
            List<JournalEntryLine> draftLines, @Nullable List<LineSpec> matchedLineSpecs) {
        Map<UUID, GLAccount> accounts = loadAccounts(draftLines);
        List<ResolvedLine> resolvedLines = new ArrayList<>();

        for (int i = 0; i < draftLines.size(); i++) {
            JournalEntryLine line = draftLines.get(i);
            UUID accountId = line.getGlAccountId();
            GLAccount account = accountId != null ? accounts.get(accountId) : null;
            LineSpec spec = matchedLineSpecs != null && i < matchedLineSpecs.size() ? matchedLineSpecs.get(i) : null;

            resolvedLines.add(ResolvedLine.builder()
                    .glAccountId(accountId)
                    .accountCode(account != null ? account.getAccountCode() : null)
                    .accountName(account != null ? account.getAccountName() : null)
                    .debitAmount(line.getDebitAmount())
                    .creditAmount(line.getCreditAmount())
                    .description(line.getDescription())
                    .splitGroup(spec != null ? spec.splitGroup() : null)
                    .factorPercent(spec != null ? spec.factorPercent() : null)
                    .build());
        }
        return resolvedLines;
    }

    private Map<UUID, GLAccount> loadAccounts(List<JournalEntryLine> draftLines) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (JournalEntryLine line : draftLines) {
            UUID id = line.getGlAccountId();
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<UUID, GLAccount> byId = new LinkedHashMap<>();
        for (GLAccount account : glAccountRepository.findAllById(ids)) {
            byId.put(account.getGlAccountId(), account);
        }
        return byId;
    }

    // ── No-match path ─────────────────────────────────────────────────────────

    private MappingResolutionTestResponse buildNoMatchResponse(
            MappingResolutionTestRequest request, AccountingEvent event, PostingResult result) {
        String reason =
                result.getFailureReason() != null ? result.getFailureReason().name() : "NO_RULE_VERSION";

        // A rule version was selected but no condition matched: surface the
        // per-predicate outcomes so the caller can see why.
        PostingRuleVersion version = null;
        UUID versionId = asUuid(result.getEvaluationDetails(), "ruleVersionUsed");
        if (versionId != null) {
            version = versionRepository.findById(versionId).orElse(null);
        }
        ConditionScan scan = scanConditions(version, event);

        return MappingResolutionTestResponse.builder()
                .matched(false)
                .noMatchReason(reason)
                .noMatchDetail(result.getFailureDetails())
                .predicateEvaluations(scan.predicateEvaluations.isEmpty() ? null : scan.predicateEvaluations)
                .build();
    }

    // ── Rule-definition rescan (metadata enrichment) ───────────────────────────

    /**
     * Parses the matched rule version's {@code rulesDefinition} and replays
     * the evaluator's first-match condition loop to recover per-predicate
     * (E2) outcomes and, for the matched condition, its line specs (E1
     * {@code splitGroup}/{@code factorPercent}). Kept read-only and free of
     * side effects.
     */
    private ConditionScan scanConditions(@Nullable PostingRuleVersion version, AccountingEvent event) {
        ConditionScan scan = new ConditionScan();
        if (version == null) {
            return scan;
        }
        String rulesJson = version.getRulesDefinition();
        if (rulesJson == null || rulesJson.isBlank() || "{}".equals(rulesJson.trim())) {
            return scan;
        }

        JsonNode conditions;
        try {
            JsonNode root = objectMapper.readTree(rulesJson);
            conditions = root.get("conditions");
        } catch (Exception e) {
            log.warn(
                    "Dry-run could not parse rulesDefinition for version {}: {}",
                    version.getVersionId(),
                    e.getMessage());
            return scan;
        }
        if (conditions == null || !conditions.isArray()) {
            return scan;
        }

        for (JsonNode conditionBlock : conditions) {
            String conditionExpr = conditionBlock.has("condition")
                    ? conditionBlock.get("condition").asString()
                    : null;
            String predicateLabel = conditionExpr != null ? conditionExpr : "*";
            scan.keysEvaluated.add(predicateLabel);

            PredicateOutcome outcome = evaluatePredicate(conditionExpr, event);
            scan.predicateEvaluations.add(PredicateEvaluation.builder()
                    .predicate(predicateLabel)
                    .matched(outcome.matched())
                    .detail(outcome.detail())
                    .build());

            if (!outcome.matched()) {
                continue;
            }

            JsonNode linesNode = conditionBlock.get("lines");
            if (linesNode == null || !linesNode.isArray() || linesNode.isEmpty()) {
                // Mirror the evaluator: a matched-but-empty condition is skipped.
                continue;
            }

            // First condition that matches and has lines is the one the
            // evaluator resolved; capture its line specs and stop.
            scan.matchedLineSpecs = parseLineSpecs(linesNode);
            break;
        }
        return scan;
    }

    private List<LineSpec> parseLineSpecs(JsonNode linesNode) {
        List<LineSpec> specs = new ArrayList<>();
        for (JsonNode lineNode : linesNode) {
            String amountField =
                    lineNode.has("amountField") ? lineNode.get("amountField").asString() : null;
            BigDecimal factorPercent = null;
            if (lineNode.has("factorPercent")) {
                try {
                    factorPercent = new BigDecimal(lineNode.get("factorPercent").asString());
                } catch (NumberFormatException e) {
                    factorPercent = null;
                }
            }
            String splitGroup =
                    lineNode.has("splitGroup") ? lineNode.get("splitGroup").asString() : null;
            specs.add(new LineSpec(amountField, factorPercent, splitGroup));
        }
        return specs;
    }

    // ── Predicate evaluation (E2 grammar reuse) ────────────────────────────────

    private PredicateOutcome evaluatePredicate(@Nullable String conditionExpr, AccountingEvent event) {
        if (conditionExpr == null || conditionExpr.isBlank() || "*".equals(conditionExpr.trim())) {
            return new PredicateOutcome(true, null);
        }
        PredicateParser.Predicate predicate;
        try {
            predicate = PredicateParser.parse(conditionExpr);
        } catch (PredicateParser.ParseException e) {
            return new PredicateOutcome(false, "Predicate could not be parsed: " + e.getMessage());
        }

        boolean matched = predicate.matches(event.getEventType(), event.getPayload());
        if (matched) {
            return new PredicateOutcome(true, null);
        }
        String failingClause = firstFailingClause(predicate, event);
        String detail = failingClause != null
                ? "clause " + failingClause + " evaluated false"
                : "Predicate '" + conditionExpr + "' did not match the sample event";
        return new PredicateOutcome(false, detail);
    }

    @Nullable
    private String firstFailingClause(PredicateParser.Predicate predicate, AccountingEvent event) {
        for (PredicateParser.Clause clause : predicate.clauses()) {
            boolean clauseMatched =
                    new PredicateParser.Predicate(List.of(clause)).matches(event.getEventType(), event.getPayload());
            if (!clauseMatched) {
                return renderClause(clause);
            }
        }
        return null;
    }

    private String renderClause(PredicateParser.Clause clause) {
        String lhs;
        if (clause.lhs() instanceof PredicateParser.EventTypeRef) {
            lhs = "eventType";
        } else if (clause.lhs() instanceof PredicateParser.PayloadPath path) {
            lhs = "payload." + String.join(".", path.segments());
        } else {
            lhs = "?";
        }
        String rhs =
                switch (clause.literal()) {
                    case PredicateParser.StringLiteral s -> "'" + s.value() + "'";
                    case PredicateParser.NumberLiteral n -> n.value().toPlainString();
                };
        return lhs + " " + clause.op().symbol() + " " + rhs;
    }

    // ── Amount interpretability guard ──────────────────────────────────────────

    /**
     * Ensures every {@code amountField} referenced by the matched condition
     * that is present in the payload is numeric-coercible. An absent field
     * mirrors the real engine (resolves to zero) and is allowed; a
     * present-but-non-numeric value is a caller error mapped to HTTP 400.
     */
    private void assertAmountFieldsInterpretable(List<LineSpec> specs, @Nullable Map<String, Object> payload) {
        Set<String> checked = new LinkedHashSet<>();
        for (LineSpec spec : specs) {
            String field = spec.amountField();
            if (field == null || field.isBlank() || !checked.add(field)) {
                continue;
            }
            Object value = navigatePayload(field, payload);
            if (value == null) {
                continue; // absent → engine uses zero, mirror it
            }
            if (!isNumeric(value)) {
                throw new IllegalArgumentException("Sample payload field '" + field + "' value '" + value
                        + "' cannot be interpreted as a numeric amount");
            }
        }
    }

    @Nullable
    private Object navigatePayload(String amountField, @Nullable Map<String, Object> payload) {
        String fieldPath =
                amountField.startsWith("payload.") ? amountField.substring("payload.".length()) : amountField;
        Object current = payload;
        for (String part : fieldPath.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private boolean isNumeric(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (value instanceof String str) {
            try {
                new BigDecimal(str.trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    // ── Small helpers ──────────────────────────────────────────────────────────

    @Nullable
    private String asString(@Nullable Map<String, Object> details, String key) {
        if (details == null) {
            return null;
        }
        Object value = details.get(key);
        return value != null ? value.toString() : null;
    }

    @Nullable
    private UUID asUuid(@Nullable Map<String, Object> details, String key) {
        if (details == null) {
            return null;
        }
        Object value = details.get(key);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return null;
    }

    // ── Value carriers ─────────────────────────────────────────────────────────

    private record LineSpec(
            @Nullable String amountField,
            @Nullable BigDecimal factorPercent,
            @Nullable String splitGroup) {}

    private record PredicateOutcome(
            boolean matched, @Nullable String detail) {}

    private static final class ConditionScan {
        private final List<String> keysEvaluated = new ArrayList<>();
        private final List<PredicateEvaluation> predicateEvaluations = new ArrayList<>();

        @Nullable
        private List<LineSpec> matchedLineSpecs;
    }
}
