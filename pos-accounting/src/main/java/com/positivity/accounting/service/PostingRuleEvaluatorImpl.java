package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.PostingRuleSet;
import com.positivity.accounting.internal.entity.PostingRuleVersion;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.JournalEntryType;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.enums.PostingRuleSetState;
import com.positivity.accounting.internal.repository.PostingRuleSetRepository;
import com.positivity.accounting.internal.repository.PostingRuleVersionRepository;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of posting rule evaluator.
 * Loads applicable rule versions and produces deterministic mappings from
 * events to journal entries.
 * 
 * Core Algorithm:
 * 1. Load PUBLISHED PostingRuleVersion for org + transaction date
 * 2. Parse rulesDefinition JSON and evaluate conditions
 * 3. Attempt exact match → fallback match → category default
 * 4. Generate balanced JournalEntry from matched mapping
 * 5. Return PostingResult with outcome
 * 
 * TODO [CAP-278]: This is a foundational implementation. The actual mapping
 * logic
 * requires full understanding of rulesDefinition schema and mapping table
 * structures.
 * Current implementation provides the framework; mapping evaluation needs
 * enhancement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostingRuleEvaluatorImpl implements PostingRuleEvaluator {

    private final PostingRuleVersionRepository versionRepository;
    private final PostingRuleSetRepository ruleSetRepository;
    private final JournalEntryService journalEntryService;

    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.0001");

    @Override
    @NonNull
    public PostingResult evaluateEvent(@NonNull AccountingEvent event, @Nullable UUID mappingVersionToUse) {
        log.debug("Evaluating posting rules for event {} org {} date {}",
                event.getEventId(), event.getOrganizationId(), event.getTransactionDate());

        // Validation
        if (event.getOrganizationId() == null) {
            return PostingResult.failure(PostingFailureReason.VALIDATION_ERROR,
                    "Event missing organizationId");
        }
        if (event.getTransactionDate() == null) {
            return PostingResult.failure(PostingFailureReason.VALIDATION_ERROR,
                    "Event missing transactionDate");
        }
        if (event.getEventType() == null || event.getEventType().isBlank()) {
            return PostingResult.failure(PostingFailureReason.VALIDATION_ERROR,
                    "Event missing eventType");
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
                String msg = String.format("No published rule version found for org %s on %s",
                        event.getOrganizationId(), event.getTransactionDate());
                evaluationDetails.put("failureStep", "loadRuleVersion");
                return PostingResult.failure(PostingFailureReason.NO_RULE_VERSION, msg, evaluationDetails);
            }

            evaluationDetails.put("ruleVersionUsed", ruleVersion.getVersionId());
            evaluationDetails.put("ruleVersionNumber", ruleVersion.getVersionNumber());

            // 2. Evaluate mapping keys (exact → fallback → category default)
            MappingEvaluation mappingEval = evaluateMappingKeys(event, ruleVersion);
            if (!mappingEval.success) {
                evaluationDetails.put("failureStep", "evaluateMappingKeys");
                evaluationDetails.put("keysEvaluated", mappingEval.keysEvaluated);
                return PostingResult.failure(
                        PostingFailureReason.UNMAPPED_EVENT_TYPE,
                        "No mapping found for event type " + event.getEventType(),
                        evaluationDetails);
            }

            evaluationDetails.put("mappingType", mappingEval.mappingType);
            evaluationDetails.put("mappingKey", mappingEval.mappingKey);

            // 3. Generate journal entry draft
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

            evaluationDetails.put("journalEntryLineCount", journalEntry.getLines().size());
            evaluationDetails.put("journalEntryAmount", calculateDebitTotal(journalEntry));

            // 5. Return success result
            log.info("Successfully evaluated event {} with mapping version {}",
                    event.getEventId(), ruleVersion.getVersionId());
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
     * Uses mappingVersionToUse if provided, otherwise finds active PUBLISHED
     * version
     * for the organization and transaction date.
     */
    private PostingRuleVersion loadRuleVersion(AccountingEvent event, UUID mappingVersionToUse) {
        if (mappingVersionToUse != null) {
            return versionRepository.findById(mappingVersionToUse).orElse(null);
        }

        // Find rule sets matching event type
        // TODO [CAP-278]: Add organization filtering and effective date checking
        List<PostingRuleSet> ruleSets = ruleSetRepository.findByEventType(event.getEventType());
        if (ruleSets.isEmpty()) {
            return null;
        }

        // For each rule set, find PUBLISHED versions
        for (PostingRuleSet ruleSet : ruleSets) {
            List<PostingRuleVersion> publishedVersions = versionRepository.findByPostingRuleSetIdAndState(
                    ruleSet.getPostingRuleSetId(),
                    PostingRuleSetState.PUBLISHED);

            if (!publishedVersions.isEmpty()) {
                // Return the first published version found
                // TODO [CAP-278]: Implement proper org + date filtering to select the correct
                // version
                return publishedVersions.get(0);
            }
        }

        return null;
    }

    /**
     * Evaluates mapping keys in order: exact match → fallback match → category
     * default.
     * Returns information about the matched mapping or failure.
     */
    private MappingEvaluation evaluateMappingKeys(AccountingEvent event, PostingRuleVersion ruleVersion) {
        MappingEvaluation eval = new MappingEvaluation();
        eval.keysEvaluated = new ArrayList<>();

        // TODO [CAP-278]: Implement actual mapping key evaluation logic
        // This requires parsing rulesDefinition JSON and checking against mapping
        // tables

        // For now, simulate a successful exact match
        // In production, this would:
        // 1. Parse event.payload to extract dimension values
        // 2. Check GLMapping table for exact dimension match
        // 3. If not found, check for fallback matches
        // 4. If not found, check for category default

        eval.success = true;
        eval.mappingType = "exact"; // or "fallback" or "category_default"
        eval.mappingKey = event.getEventType();
        eval.keysEvaluated.add(event.getEventType());

        return eval;
    }

    /**
     * Generates a balanced JournalEntry from the mapping evaluation result.
     */
    private JournalEntry generateJournalEntry(AccountingEvent event, MappingEvaluation mappingEval,
            PostingRuleVersion ruleVersion) {
        // TODO [CAP-278]: Implement actual journal entry generation from mapping rules
        // This would parse the mapping result and create JournalEntryLine objects
        // based on the account codes and amounts from the mapping definition

        JournalEntry entry = new JournalEntry();
        entry.setJournalEntryId(UUIDv7Generator.generate());
        entry.setSourceEventId(event.getEventId());
        entry.setSourceEventType(event.getEventType());
        entry.setPostingRuleVersionId(ruleVersion.getVersionId());
        entry.setPostingRuleSetId(ruleVersion.getPostingRuleSetId());
        entry.setEntryType(JournalEntryType.EVENT_DRIVEN);
        entry.setTransactionDate(event.getTransactionDate());
        entry.setStatus(JournalEntryStatus.DRAFT);
        entry.setCreatedAt(Instant.now());
        entry.setModifiedAt(Instant.now());

        // TODO [CAP-278]: Parse rulesDefinition and create actual lines
        // For now, create a simple balanced entry as a placeholder
        List<JournalEntryLine> lines = new ArrayList<>();

        // Example: Create a balanced entry with debit and credit
        // In production, this would come from the mapping rules
        JournalEntryLine debitLine = new JournalEntryLine();
        debitLine.setLineId(UUIDv7Generator.generate());
        debitLine.setJournalEntryId(entry.getJournalEntryId());
        debitLine.setGlAccountId(UUID.randomUUID()); // TODO: Get from mapping
        debitLine.setDebitAmount(new BigDecimal("100.00"));
        debitLine.setCreditAmount(BigDecimal.ZERO);
        lines.add(debitLine);

        JournalEntryLine creditLine = new JournalEntryLine();
        creditLine.setLineId(UUIDv7Generator.generate());
        creditLine.setJournalEntryId(entry.getJournalEntryId());
        creditLine.setGlAccountId(UUID.randomUUID()); // TODO: Get from mapping
        creditLine.setDebitAmount(BigDecimal.ZERO);
        creditLine.setCreditAmount(new BigDecimal("100.00"));
        lines.add(creditLine);

        entry.setLines(lines);

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

    /**
     * Internal class to hold mapping evaluation results.
     */
    private static class MappingEvaluation {
        boolean success;
        String mappingType; // "exact", "fallback", "category_default"
        String mappingKey;
        List<String> keysEvaluated;
    }
}
