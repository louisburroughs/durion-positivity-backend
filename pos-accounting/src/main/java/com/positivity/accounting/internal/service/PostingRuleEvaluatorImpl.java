package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.DefaultGLMappingProperties;
import com.positivity.accounting.internal.dto.MappingEvaluation;
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
import com.positivity.accounting.service.GLMappingResolver;
import com.positivity.accounting.service.PostingRuleEvaluator;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Implementation of posting rule evaluator.
 * Loads applicable rule versions and produces deterministic mappings from
 * events to journal entries.
 *
 * Core Algorithm:
 * 1. Load PUBLISHED PostingRuleVersion for event type
 * 2. Parse rulesDefinition JSON and evaluate conditions against event payload
 * 3. Resolve GL accounts via GLMappingResolver (exact → fallback → category
 * default)
 * 4. Generate balanced JournalEntry from resolved mappings
 * 5. Return PostingResult with outcome
 *
 * rulesDefinition JSON schema:
 *
 * <pre>
 * {
 *   "conditions": [
 *     {
 *       "condition": "eventType == 'billing.invoicePosted'",
 *       "lines": [
 *         {
 *           "postingCategoryId": "uuid",
 *           "mappingKeyId": "uuid",
 *           "side": "DEBIT",
 *           "amountField": "payload.amount",
 *           "description": "Accounts Receivable"
 *         },
 *         {
 *           "postingCategoryId": "uuid",
 *           "mappingKeyId": "uuid",
 *           "side": "CREDIT",
 *           "amountField": "payload.amount",
 *           "description": "Revenue"
 *         }
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 *
 * E1 additions (issue #945): a line may optionally carry
 * {@code "factorPercent"} (decimal 0–100, up to 4 decimal places) and
 * {@code "splitGroup"} (string marker). Lines sharing a splitGroup within one
 * condition split their shared amountField proportionally; shares are rounded
 * HALF_UP to 2 decimal places and the rounding residual is placed on the line
 * with the largest absolute raw share (first in rule order on ties), so the
 * group always sums exactly to the source amount. Full schema:
 * durion {@code domains/accounting/.business-rules/POSTING_RULES_SCHEMA.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostingRuleEvaluatorImpl implements PostingRuleEvaluator {
    private final Clock clock;

    private final PostingRuleVersionRepository versionRepository;
    private final PostingRuleSetRepository ruleSetRepository;
    private final GLMappingResolver glMappingResolver;
    private final DefaultGLMappingRepository defaultGLMappingRepository;
    private final DefaultGLMappingProperties defaultGLMappingProperties;
    private final ObjectMapper objectMapper;

    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.0001");

    /** Currency scale used when rounding proportional split shares (E1). */
    private static final int SPLIT_AMOUNT_SCALE = 2;

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    @Override
    @NonNull
    public PostingResult evaluateEvent(@NonNull AccountingEvent event, @Nullable UUID mappingVersionToUse) {
        log.debug(
                "Evaluating posting rules for event {} org {} date {}",
                event.getEventId(),
                event.getOrganizationId(),
                event.getTransactionDate());

        // Validation
        if (event.getOrganizationId() == null) {
            return PostingResult.failure(PostingFailureReason.VALIDATION_ERROR, "Event missing organizationId");
        }
        if (event.getTransactionDate() == null) {
            return PostingResult.failure(PostingFailureReason.VALIDATION_ERROR, "Event missing transactionDate");
        }
        if (event.getEventType() == null || event.getEventType().isBlank()) {
            return PostingResult.failure(PostingFailureReason.VALIDATION_ERROR, "Event missing eventType");
        }

        Map<String, Object> evaluationDetails = new HashMap<>();
        evaluationDetails.put("eventId", event.getEventId());
        evaluationDetails.put("eventType", event.getEventType());
        evaluationDetails.put("organizationId", event.getOrganizationId());
        evaluationDetails.put("transactionDate", event.getTransactionDate());

        try {
            // 1. Load applicable PostingRuleVersion
            PostingRuleVersion ruleVersion = loadRuleVersion(event, mappingVersionToUse);
            if (ruleVersion == null) {
                // Try fallback to default GL mapping (if feature is enabled)
                if (defaultGLMappingProperties.isEnabled()) {
                    DefaultGLMapping defaultMapping = loadDefaultGLMapping(event);
                    if (defaultMapping != null) {
                        log.debug(
                                "Using default GL mapping {} for eventType '{}'",
                                defaultMapping.getMappingId(),
                                event.getEventType());
                        JournalEntry journalEntry = generateJournalEntryFromDefault(event, defaultMapping);

                        if (!isBalanced(journalEntry)) {
                            evaluationDetails.put("failureStep", "balanceValidation");
                            evaluationDetails.put("debitTotal", calculateDebitTotal(journalEntry));
                            evaluationDetails.put("creditTotal", calculateCreditTotal(journalEntry));
                            return PostingResult.failure(
                                    PostingFailureReason.UNBALANCED_JOURNAL,
                                    "Generated journal entry from default mapping is not balanced",
                                    evaluationDetails);
                        }

                        evaluationDetails.put("mappingType", "defaultGLMapping");
                        evaluationDetails.put("defaultMappingId", defaultMapping.getMappingId());
                        evaluationDetails.put(
                                "journalEntryLineCount", journalEntry.getLines().size());
                        evaluationDetails.put("journalEntryAmount", calculateDebitTotal(journalEntry));

                        log.info(
                                "Successfully evaluated event {} with default GL mapping {}",
                                event.getEventId(),
                                defaultMapping.getMappingId());
                        return PostingResult.success(journalEntry, null, evaluationDetails);
                    }
                }

                // No rule version and no default mapping (or feature disabled) - fail
                String msg = String.format(
                        "No published rule version or default GL mapping found for eventType '%s' on %s%s",
                        event.getEventType(),
                        event.getTransactionDate(),
                        defaultGLMappingProperties.isEnabled() ? "" : " (default mappings disabled)");
                evaluationDetails.put("failureStep", "loadRuleVersion");
                return PostingResult.failure(PostingFailureReason.NO_RULE_VERSION, msg, evaluationDetails);
            }

            evaluationDetails.put("ruleVersionUsed", ruleVersion.getVersionId());
            evaluationDetails.put("ruleVersionNumber", ruleVersion.getVersionNumber());

            // 2. Evaluate mapping keys: parse rulesDefinition and resolve GL accounts
            MappingEvaluation mappingEval = evaluateMappingKeys(event, ruleVersion);
            if (!mappingEval.isSuccess()) {
                evaluationDetails.put("failureStep", "evaluateMappingKeys");
                evaluationDetails.put("keysEvaluated", mappingEval.getKeysEvaluated());
                return PostingResult.failure(
                        PostingFailureReason.UNMAPPED_EVENT_TYPE,
                        "No mapping found for event type " + event.getEventType(),
                        evaluationDetails);
            }

            evaluationDetails.put("mappingType", mappingEval.getMappingType());
            evaluationDetails.put("mappingKey", mappingEval.getMappingKey());

            // 3. Generate journal entry draft from resolved lines
            JournalEntry journalEntry = generateJournalEntry(event, mappingEval, ruleVersion);

            // 4. Validate balance
            if (!isBalanced(journalEntry)) {
                evaluationDetails.put("failureStep", "balanceValidation");
                evaluationDetails.put("debitTotal", calculateDebitTotal(journalEntry));
                evaluationDetails.put("creditTotal", calculateCreditTotal(journalEntry));
                return PostingResult.failure(
                        PostingFailureReason.UNBALANCED_JOURNAL,
                        "Generated journal entry is not balanced",
                        evaluationDetails);
            }

            evaluationDetails.put(
                    "journalEntryLineCount", journalEntry.getLines().size());
            evaluationDetails.put("journalEntryAmount", calculateDebitTotal(journalEntry));

            // 5. Return success result
            log.info(
                    "Successfully evaluated event {} with mapping version {}",
                    event.getEventId(),
                    ruleVersion.getVersionId());
            return PostingResult.success(journalEntry, ruleVersion.getVersionId(), evaluationDetails);

        } catch (Exception e) {
            log.error("Error evaluating posting rules for event {}", event.getEventId(), e);
            evaluationDetails.put("exception", e.getClass().getSimpleName());
            evaluationDetails.put("exceptionMessage", e.getMessage());
            return PostingResult.failure(
                    PostingFailureReason.INTERNAL_ERROR,
                    "Internal error during rule evaluation: " + e.getMessage(),
                    evaluationDetails);
        }
    }

    /**
     * Loads the applicable PostingRuleVersion for the given event.
     * Uses mappingVersionToUse if provided, otherwise finds the active PUBLISHED
     * version matching the event type.
     *
     * Note: PostingRuleSet does not currently have an organizationId field, so
     * filtering is by eventType only. Organization-scoped rule sets can be added
     * when multi-tenant rule management is required.
     */
    private PostingRuleVersion loadRuleVersion(AccountingEvent event, UUID mappingVersionToUse) {
        if (mappingVersionToUse != null) {
            log.debug("Loading specific rule version: {}", mappingVersionToUse);
            return versionRepository.findById(mappingVersionToUse).orElse(null);
        }

        // Find rule sets matching event type
        List<PostingRuleSet> ruleSets = ruleSetRepository.findByEventType(event.getEventType());
        if (ruleSets.isEmpty()) {
            log.debug("No rule sets found for eventType '{}'", event.getEventType());
            return null;
        }

        // For each rule set, find PUBLISHED versions (most recent first via @OrderBy)
        for (PostingRuleSet ruleSet : ruleSets) {
            List<PostingRuleVersion> publishedVersions =
                    versionRepository.findByPostingRuleSet_PostingRuleSetIdAndState(
                            ruleSet.getPostingRuleSetId(), PostingRuleSetState.PUBLISHED);

            if (!publishedVersions.isEmpty()) {
                PostingRuleVersion selected = publishedVersions.get(0);
                log.debug(
                        "Selected rule version {} (v{}) from rule set '{}' for eventType '{}'",
                        selected.getVersionId(),
                        selected.getVersionNumber(),
                        ruleSet.getName(),
                        event.getEventType());
                return selected;
            }
        }

        log.debug("No PUBLISHED versions found for eventType '{}'", event.getEventType());
        return null;
    }

    /**
     * Loads the default GL mapping for an event when no posting rule version
     * exists.
     * Resolution priority (when allowGlobalDefaults=true):
     * 1. Exact match: eventType + organizationId
     * 2. Global default: eventType + organizationId IS NULL
     *
     * When allowGlobalDefaults=false, only org-specific mappings are returned.
     *
     * @param event the accounting event
     * @return default GL mapping if found, null otherwise
     */
    private DefaultGLMapping loadDefaultGLMapping(AccountingEvent event) {
        if (defaultGLMappingProperties.isAllowGlobalDefaults()) {
            return defaultGLMappingRepository
                    .findActiveDefaultForEvent(event.getEventType(), event.getOrganizationId())
                    .orElse(null);
        } else {
            // Only allow org-specific defaults (no global fallback)
            return defaultGLMappingRepository
                    .findActiveDefaultForEvent(event.getEventType(), event.getOrganizationId())
                    .filter(mapping -> mapping.getOrganizationId() != null)
                    .orElse(null);
        }
    }

    /**
     * Generates a simple balanced journal entry from a default GL mapping.
     * Uses the event's payload.amount field for the transaction amount.
     *
     * @param event          the accounting event
     * @param defaultMapping the default GL mapping
     * @return generated journal entry with two lines (debit + credit)
     */
    private JournalEntry generateJournalEntryFromDefault(AccountingEvent event, DefaultGLMapping defaultMapping) {
        JournalEntry entry = new JournalEntry();
        entry.setJournalEntryId(UUIDv7Generator.generate());
        entry.setSourceEventId(event.getEventId());
        entry.setSourceEventType(event.getEventType());
        entry.setPostingRuleVersionId(null); // No rule version - using default mapping
        entry.setPostingRuleSetId(null);
        entry.setEntryType(JournalEntryType.EVENT_DRIVEN);
        entry.setTransactionDate(event.getTransactionDate());
        entry.setStatus(JournalEntryStatus.DRAFT);
        entry.setDescription("Auto-generated from default GL mapping for " + event.getEventType());
        entry.setCreatedAt(Instant.now(clock));
        entry.setUpdatedAt(Instant.now(clock));

        // Resolve amount from event payload
        BigDecimal amount = resolveAmount("payload.amount", event);
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            if (defaultGLMappingProperties.isRequireAmountField()) {
                throw new IllegalArgumentException(
                        "Event " + event.getEventId() + " has zero or missing payload.amount field, "
                                + "which is required when using default GL mappings (pos.accounting.default-mappings.require-amount-field=true)");
            }
            log.warn("Event {} has zero or missing amount - using 0.01 for traceability", event.getEventId());
            amount = new BigDecimal("0.01");
        }

        // Create debit line
        JournalEntryLine debitLine = new JournalEntryLine();
        debitLine.setLineId(UUIDv7Generator.generate());
        debitLine.setJournalEntry(entry);
        debitLine.setGlAccountId(defaultMapping.getDebitAccountId());
        debitLine.setDebitAmount(amount);
        debitLine.setCreditAmount(BigDecimal.ZERO);
        debitLine.setDescription(
                defaultMapping.getDescription() != null
                        ? defaultMapping.getDescription() + " (DR)"
                        : "Default debit for " + event.getEventType());

        // Create credit line
        JournalEntryLine creditLine = new JournalEntryLine();
        creditLine.setLineId(UUIDv7Generator.generate());
        creditLine.setJournalEntry(entry);
        creditLine.setGlAccountId(defaultMapping.getCreditAccountId());
        creditLine.setDebitAmount(BigDecimal.ZERO);
        creditLine.setCreditAmount(amount);
        creditLine.setDescription(
                defaultMapping.getDescription() != null
                        ? defaultMapping.getDescription() + " (CR)"
                        : "Default credit for " + event.getEventType());

        List<JournalEntryLine> lines = new ArrayList<>();
        lines.add(debitLine);
        lines.add(creditLine);

        entry.setLines(lines);
        entry.setTotalDebits(amount);
        entry.setTotalCredits(amount);

        // Initialize line numbers
        entry.initializeLines();

        return entry;
    }

    /**
     * Evaluates mapping keys by parsing the rulesDefinition JSON from the rule
     * version
     * and resolving GL accounts via GLMappingResolver.
     *
     * Resolution order: exact match → fallback match → category default
     * (handled by GLMappingResolver internally).
     *
     * @param event       the accounting event
     * @param ruleVersion the selected posting rule version
     * @return evaluation result with resolved GL account lines
     */
    private MappingEvaluation evaluateMappingKeys(AccountingEvent event, PostingRuleVersion ruleVersion) {
        MappingEvaluation eval = new MappingEvaluation();
        String rulesJson = ruleVersion.getRulesDefinition();

        // Handle empty/null rules (e.g. newly-created stub rule sets)
        if (rulesJson == null || rulesJson.isBlank() || "{}".equals(rulesJson.trim())) {
            log.warn(
                    "Rules definition is empty for version {} - cannot generate valid journal entry",
                    ruleVersion.getVersionId());
            eval.setSuccess(false);
            eval.addEvaluatedKey("(empty rulesDefinition)");
            return eval;
        }

        try {
            JsonNode root = objectMapper.readTree(rulesJson);
            JsonNode conditions = root.get("conditions");

            if (conditions == null || !conditions.isArray() || conditions.isEmpty()) {
                log.warn(
                        "No conditions in rulesDefinition for version {} - cannot generate valid journal entry",
                        ruleVersion.getVersionId());
                eval.setSuccess(false);
                eval.addEvaluatedKey("(no conditions defined)");
                return eval;
            }

            // Extract event dimensions for GL resolution
            Map<String, String> dimensions = extractDimensions(event);

            // Evaluate each condition block; first matching condition wins
            for (JsonNode conditionBlock : conditions) {
                String conditionExpr = conditionBlock.has("condition")
                        ? conditionBlock.get("condition").asString()
                        : null;

                eval.addEvaluatedKey(conditionExpr != null ? conditionExpr : "(default)");

                if (!matchesCondition(conditionExpr, event)) {
                    continue;
                }

                // Condition matched — resolve GL lines
                JsonNode linesNode = conditionBlock.get("lines");
                if (linesNode == null || !linesNode.isArray() || linesNode.isEmpty()) {
                    log.warn("Condition '{}' matched but has no lines", conditionExpr);
                    continue;
                }

                List<MappingEvaluation.ResolvedLine> resolvedLines =
                        resolveConditionLines(conditionExpr, linesNode, event, dimensions);

                if (resolvedLines != null && !resolvedLines.isEmpty()) {
                    eval.setSuccess(true);
                    eval.setMappingType("exact");
                    eval.setMappingKey(conditionExpr != null ? conditionExpr : event.getEventType());
                    eval.setResolvedLines(resolvedLines);
                    return eval;
                }
            }

            // No condition matched
            eval.setSuccess(false);
            return eval;

        } catch (IllegalStateException e) {
            // Invalid split-group configuration in a published rules
            // definition (should be impossible for versions published after
            // E1's publish-time validation). Propagate so evaluateEvent maps
            // it to an explicit INTERNAL_ERROR failure instead of silently
            // "rebalancing" the group via residual distribution.
            throw e;
        } catch (Exception e) {
            log.error(
                    "Failed to parse rulesDefinition JSON for version {}: {}",
                    ruleVersion.getVersionId(),
                    e.getMessage());
            eval.setSuccess(false);
            return eval;
        }
    }

    /**
     * Resolves all lines of a matched condition into journal-entry line
     * definitions, honoring E1 proportional split groups.
     *
     * <p>Pass 1 parses each line and resolves its GL account (posting
     * category + mapping key first, direct {@code glAccountId} fallback —
     * unchanged from pre-E1 behavior). Pass 2 computes line amounts:
     * non-split lines resolve their {@code amountField} directly (no
     * rounding, exactly as before); split-group lines share one resolved
     * amount that is distributed by {@code factorPercent} with deterministic
     * residual placement (see {@link #distributeSplitGroup}).
     *
     * @return resolved lines in rule order, or {@code null} when any line's
     *         GL account could not be resolved (condition treated as
     *         unresolvable, matching pre-E1 behavior)
     * @throws IllegalStateException when a split group in the published rules
     *                               violates the E1 invariants (mapped to an
     *                               INTERNAL_ERROR posting failure upstream)
     */
    @Nullable
    private List<MappingEvaluation.ResolvedLine> resolveConditionLines(
            String conditionExpr, JsonNode linesNode, AccountingEvent event, Map<String, String> dimensions) {
        List<RuleLineSpec> specs = new ArrayList<>();

        for (JsonNode lineNode : linesNode) {
            String side = lineNode.has("side") ? lineNode.get("side").asString() : "DEBIT";
            String amountField =
                    lineNode.has("amountField") ? lineNode.get("amountField").asString() : null;
            String description =
                    lineNode.has("description") ? lineNode.get("description").asString() : null;
            BigDecimal factorPercent = parseFactorPercent(lineNode);
            String splitGroup = parseSplitGroup(lineNode);

            // Resolve GL account: try postingCategoryId+mappingKeyId first,
            // then fall back to direct glAccountId reference
            UUID glAccountId = null;

            if (lineNode.has("postingCategoryId") && lineNode.has("mappingKeyId")) {
                UUID postingCategoryId =
                        UUID.fromString(lineNode.get("postingCategoryId").asString());
                UUID mappingKeyId = UUID.fromString(lineNode.get("mappingKeyId").asString());

                try {
                    glAccountId = glMappingResolver.resolveGLAccount(
                            postingCategoryId, mappingKeyId, event.getTransactionDate(), dimensions);
                } catch (IllegalArgumentException e) {
                    log.warn(
                            "GL mapping resolution failed for category={} key={}: {}",
                            postingCategoryId,
                            mappingKeyId,
                            e.getMessage());
                    return null;
                }
            } else if (lineNode.has("glAccountId")) {
                // Direct GL account reference (simpler rules without mapping tables)
                glAccountId = UUID.fromString(lineNode.get("glAccountId").asString());
            }

            if (glAccountId == null) {
                log.warn("Could not resolve GL account for line in condition '{}'", conditionExpr);
                return null;
            }

            specs.add(new RuleLineSpec(glAccountId, side, amountField, description, factorPercent, splitGroup));
        }

        BigDecimal[] amounts = computeLineAmounts(specs, event);

        List<MappingEvaluation.ResolvedLine> resolvedLines = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            RuleLineSpec spec = specs.get(i);
            BigDecimal debit = "DEBIT".equalsIgnoreCase(spec.side()) ? amounts[i] : BigDecimal.ZERO;
            BigDecimal credit = "CREDIT".equalsIgnoreCase(spec.side()) ? amounts[i] : BigDecimal.ZERO;
            resolvedLines.add(
                    new MappingEvaluation.ResolvedLine(spec.glAccountId(), debit, credit, spec.description()));
        }
        return resolvedLines;
    }

    /**
     * Computes the amount for every line of a matched condition.
     * Non-split lines resolve their own {@code amountField} (pre-E1
     * behavior, no rounding applied). Split-group lines are distributed per
     * group in first-appearance order.
     */
    private BigDecimal[] computeLineAmounts(List<RuleLineSpec> specs, AccountingEvent event) {
        BigDecimal[] amounts = new BigDecimal[specs.size()];
        Map<String, List<Integer>> groups = new LinkedHashMap<>();

        for (int i = 0; i < specs.size(); i++) {
            RuleLineSpec spec = specs.get(i);
            if (spec.splitGroup() != null) {
                groups.computeIfAbsent(spec.splitGroup(), group -> new ArrayList<>())
                        .add(i);
            } else if (spec.factorPercent() != null) {
                throw new IllegalStateException(
                        "Published rules line declares factorPercent without splitGroup (line index " + i + ")");
            } else {
                amounts[i] = resolveAmount(spec.amountField(), event);
            }
        }

        for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
            distributeSplitGroup(entry.getKey(), entry.getValue(), specs, amounts, event);
        }
        return amounts;
    }

    /**
     * Distributes one split group's shared amount across its member lines
     * (story E1, issue #945; Odoo {@code _distribute_delta_amount_smoothly}
     * reference).
     *
     * <p>Algorithm:
     * <ol>
     * <li>Resolve the group's shared {@code amountField} once.</li>
     * <li>Raw share per line = amount x factorPercent / 100, computed
     * exactly (the division by 100 is a decimal point shift).</li>
     * <li>Round each raw share HALF_UP to currency scale (2 decimal
     * places).</li>
     * <li>Residual = shared amount - sum of rounded shares (may be positive
     * or negative, and larger than one cent for extreme factor sets). The
     * whole residual is added to the line with the largest absolute raw
     * share; on a tie the first such line in rule order wins
     * (deterministic).</li>
     * </ol>
     * The group's line amounts therefore always sum exactly to the resolved
     * shared amount.
     *
     * <p>Invariants (enforced at publish time by
     * {@code PostingRuleDefinitionValidator}) are re-checked defensively
     * here; a violation in already-published rules fails the evaluation
     * explicitly rather than distorting amounts.
     */
    private void distributeSplitGroup(
            String groupName,
            List<Integer> memberIndexes,
            List<RuleLineSpec> specs,
            BigDecimal[] amounts,
            AccountingEvent event) {
        String amountField = null;
        String side = null;
        BigDecimal factorSum = BigDecimal.ZERO;

        for (int index : memberIndexes) {
            RuleLineSpec spec = specs.get(index);
            if (spec.factorPercent() == null) {
                throw new IllegalStateException("Published rules line in split group '" + groupName
                        + "' is missing factorPercent (line index " + index + ")");
            }
            if (amountField == null) {
                amountField = spec.amountField();
                side = spec.side();
            } else if (!amountField.equals(spec.amountField())) {
                throw new IllegalStateException(
                        "Published rules split group '" + groupName + "' mixes different amountField values");
            }
            if (!side.equalsIgnoreCase(spec.side())) {
                throw new IllegalStateException(
                        "Published rules split group '" + groupName + "' mixes DEBIT and CREDIT lines");
            }
            factorSum = factorSum.add(spec.factorPercent());
        }

        if (amountField == null || amountField.isBlank()) {
            throw new IllegalStateException("Published rules split group '" + groupName + "' has no amountField");
        }
        if (factorSum.compareTo(ONE_HUNDRED) != 0) {
            throw new IllegalStateException("Published rules split group '" + groupName
                    + "' factorPercent values sum to "
                    + factorSum.stripTrailingZeros().toPlainString()
                    + " instead of 100");
        }

        BigDecimal sharedAmount = resolveAmount(amountField, event);

        BigDecimal[] rawShares = new BigDecimal[memberIndexes.size()];
        BigDecimal roundedSum = BigDecimal.ZERO;
        for (int j = 0; j < memberIndexes.size(); j++) {
            RuleLineSpec spec = specs.get(memberIndexes.get(j));
            // Exact raw share: multiply then shift the decimal point (÷100)
            rawShares[j] = sharedAmount.multiply(spec.factorPercent()).movePointLeft(2);
            BigDecimal rounded = rawShares[j].setScale(SPLIT_AMOUNT_SCALE, RoundingMode.HALF_UP);
            amounts[memberIndexes.get(j)] = rounded;
            roundedSum = roundedSum.add(rounded);
        }

        BigDecimal residual = sharedAmount.subtract(roundedSum);
        if (residual.signum() != 0) {
            int target = 0;
            for (int j = 1; j < rawShares.length; j++) {
                // Strict > keeps the FIRST line in rule order on ties
                if (rawShares[j].abs().compareTo(rawShares[target].abs()) > 0) {
                    target = j;
                }
            }
            int targetIndex = memberIndexes.get(target);
            amounts[targetIndex] = amounts[targetIndex].add(residual);
            log.debug(
                    "Split group '{}': residual {} assigned to line index {} (largest raw share)",
                    groupName,
                    residual.toPlainString(),
                    targetIndex);
        }
    }

    @Nullable
    private BigDecimal parseFactorPercent(JsonNode lineNode) {
        if (!lineNode.has("factorPercent")) {
            return null;
        }
        String raw = lineNode.get("factorPercent").asString();
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Published rules line has non-numeric factorPercent '" + raw + "'", e);
        }
    }

    @Nullable
    private String parseSplitGroup(JsonNode lineNode) {
        if (!lineNode.has("splitGroup")) {
            return null;
        }
        String splitGroup = lineNode.get("splitGroup").asString();
        if (splitGroup == null || splitGroup.isBlank()) {
            throw new IllegalStateException("Published rules line has a blank splitGroup");
        }
        return splitGroup;
    }

    /**
     * Parsed definition of one rule line within a matched condition.
     */
    private record RuleLineSpec(
            UUID glAccountId,
            String side,
            @Nullable String amountField,
            @Nullable String description,
            @Nullable BigDecimal factorPercent,
            @Nullable String splitGroup) {}

    /**
     * Checks whether an event matches a simple condition expression.
     * Supports:
     * - null/blank condition → always matches (default/catch-all rule)
     * - "eventType == 'value'" → exact eventType matching
     * - "*" → wildcard, always matches
     */
    private boolean matchesCondition(String conditionExpr, AccountingEvent event) {
        if (conditionExpr == null || conditionExpr.isBlank() || "*".equals(conditionExpr.trim())) {
            return true; // Default/catch-all
        }

        // Simple eventType equality: "eventType == 'billing.invoicePosted'"
        if (conditionExpr.contains("eventType")) {
            String value = extractQuotedValue(conditionExpr);
            if (value != null) {
                return value.equals(event.getEventType());
            }
        }

        // Unrecognized condition format — treat as non-matching for safety
        log.warn("Unrecognized condition expression: '{}' — skipping", conditionExpr);
        return false;
    }

    /**
     * Extracts a single-quoted value from a simple expression like
     * "eventType == 'billing.invoicePosted'".
     */
    private String extractQuotedValue(String expression) {
        int start = expression.indexOf('\'');
        int end = expression.lastIndexOf('\'');
        if (start >= 0 && end > start) {
            return expression.substring(start + 1, end);
        }
        return null;
    }

    /**
     * Resolves an amount from the event payload.
     *
     * @param amountField dot-path into event, e.g. "payload.amount" or "amount"
     * @param event       the accounting event
     * @return resolved amount, or BigDecimal.ZERO if unresolvable
     */
    private BigDecimal resolveAmount(String amountField, AccountingEvent event) {
        if (amountField == null || amountField.isBlank()) {
            return BigDecimal.ZERO;
        }

        try {
            // Handle leading "payload." prefix - skip it since we start from
            // event.getPayload()
            String fieldPath = amountField;
            if (fieldPath.startsWith("payload.")) {
                fieldPath = fieldPath.substring("payload.".length());
            }

            // Navigate dot-path: "amount" → event.getPayload().get("amount")
            // Or "nested.amount" → event.getPayload().get("nested").get("amount")
            String[] parts = fieldPath.split("\\.");
            Object current = event.getPayload();

            for (String part : parts) {
                if (current instanceof Map<?, ?> map) {
                    current = map.get(part);
                } else {
                    return BigDecimal.ZERO;
                }
            }

            if (current instanceof Number number) {
                return new BigDecimal(number.toString());
            } else if (current instanceof String str) {
                return new BigDecimal(str);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve amount from field '{}': {}", amountField, e.getMessage());
        }

        return BigDecimal.ZERO;
    }

    /**
     * Extracts dimensional context from the event payload for GL account
     * resolution.
     * Looks for a "dimensions" map within the event payload.
     */
    private Map<String, String> extractDimensions(AccountingEvent event) {
        Map<String, Object> payload = event.getPayload();
        if (payload == null) {
            return Collections.emptyMap();
        }

        Object dims = payload.get("dimensions");
        if (dims instanceof Map<?, ?> map) {
            Map<String, String> dimensions = new HashMap<>();
            map.forEach((k, v) -> dimensions.put(String.valueOf(k), String.valueOf(v)));
            return dimensions;
        }

        return Collections.emptyMap();
    }

    /**
     * Generates a balanced JournalEntry from the MappingEvaluation's resolved
     * lines.
     * If no resolved lines are present (passthrough mode), generates a placeholder
     * entry.
     */
    private JournalEntry generateJournalEntry(
            AccountingEvent event, MappingEvaluation mappingEval, PostingRuleVersion ruleVersion) {
        JournalEntry entry = new JournalEntry();
        entry.setJournalEntryId(UUIDv7Generator.generate());
        entry.setSourceEventId(event.getEventId());
        entry.setSourceEventType(event.getEventType());
        entry.setPostingRuleVersionId(ruleVersion.getVersionId());
        entry.setPostingRuleSetId(
                ruleVersion.getPostingRuleSet() != null
                        ? ruleVersion.getPostingRuleSet().getPostingRuleSetId()
                        : null);
        entry.setEntryType(JournalEntryType.EVENT_DRIVEN);
        entry.setTransactionDate(event.getTransactionDate());
        entry.setStatus(JournalEntryStatus.DRAFT);
        entry.setDescription("Auto-generated from event " + event.getEventType());
        entry.setCreatedAt(Instant.now(clock));
        entry.setUpdatedAt(Instant.now(clock));

        List<JournalEntryLine> lines = new ArrayList<>();

        if (!mappingEval.getResolvedLines().isEmpty()) {
            // Build lines from resolved GL mappings
            BigDecimal totalDebits = BigDecimal.ZERO;
            BigDecimal totalCredits = BigDecimal.ZERO;

            for (MappingEvaluation.ResolvedLine resolved : mappingEval.getResolvedLines()) {
                JournalEntryLine line = new JournalEntryLine();
                line.setLineId(UUIDv7Generator.generate());
                line.setJournalEntry(entry);
                line.setGlAccountId(resolved.getGlAccountId());
                line.setDebitAmount(resolved.getDebitAmount());
                line.setCreditAmount(resolved.getCreditAmount());
                line.setDescription(resolved.getDescription());
                lines.add(line);

                totalDebits = totalDebits.add(resolved.getDebitAmount());
                totalCredits = totalCredits.add(resolved.getCreditAmount());
            }

            entry.setTotalDebits(totalDebits);
            entry.setTotalCredits(totalCredits);
        }
        // Note: If resolvedLines is empty, evaluateMappingKeys() should have returned
        // failure. This code path should not be reached with empty lines.

        entry.setLines(lines);

        // Initialize line numbers and parent references immediately
        entry.initializeLines();

        return entry;
    }

    /**
     * Checks if the journal entry is balanced (debits = credits within tolerance).
     */
    private boolean isBalanced(JournalEntry entry) {
        BigDecimal debitTotal = calculateDebitTotal(entry);
        BigDecimal creditTotal = calculateCreditTotal(entry);
        BigDecimal difference = debitTotal.subtract(creditTotal).abs();
        return difference.compareTo(BALANCE_TOLERANCE) <= 0;
    }

    private BigDecimal calculateDebitTotal(JournalEntry entry) {
        return entry.getLines().stream()
                .map(line -> line.getDebitAmount() != null ? line.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculateCreditTotal(JournalEntry entry) {
        return entry.getLines().stream()
                .map(line -> line.getCreditAmount() != null ? line.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
