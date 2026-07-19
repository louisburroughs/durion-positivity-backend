package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.SettlementPostingCommand;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.ProcessorSettlement;
import com.positivity.accounting.internal.entity.ProcessorSettlementLine;
import com.positivity.accounting.internal.enums.SettlementLineMatchStatus;
import com.positivity.accounting.internal.enums.SettlementStatus;
import com.positivity.accounting.internal.repository.ProcessorSettlementLineRepository;
import com.positivity.accounting.internal.repository.ProcessorSettlementRepository;
import com.positivity.accounting.service.GLMappingResolver;
import com.positivity.accounting.service.GLPostingService;
import com.positivity.accounting.service.IdempotencyService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Owns the batched settlement journal-entry posting for story F1c (issue #963,
 * decision D-13). The repository access lives here in the service layer — the
 * {@link com.positivity.accounting.internal.handler.SettlementGLPostingEventHandler}
 * only adapts the Spring event to a call on this service (ADR-0011: repositories
 * are accessed from the service layer only).
 *
 * <p>Posts one balanced journal entry per settlement via {@link
 * GLPostingService#postSettlement} (decision D-13):
 *
 * <ul>
 *   <li>Dr Cash (net bank payout)
 *   <li>Dr Processor Fees (header fees)
 *   <li>Cr Undeposited Funds (matched gross — clears the C1 receipts that reconciled)
 *   <li>Cr Settlement Suspense (unmatched gross — parked for review, decision D-14)
 * </ul>
 *
 * <p>Accounts resolve through the {@code SETTLEMENT} posting category's mapping keys — never
 * hardcoded. Idempotency key = the provider settlement id (namespaced); a replayed work item is a
 * no-op so a settlement never double-posts. Period-gate exceptions propagate unwrapped for outbox
 * retry after reopen (story B2).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementPostingService {

    static final String POSTING_CATEGORY_NAME = "SETTLEMENT";
    static final String CASH_MAPPING_KEY = "SETTLEMENT_CASH";
    static final String FEES_MAPPING_KEY = "PROCESSOR_FEES";
    static final String UNDEPOSITED_MAPPING_KEY = "UNDEPOSITED_FUNDS";
    static final String SUSPENSE_MAPPING_KEY = "SETTLEMENT_SUSPENSE";
    static final String IDEMPOTENCY_KEY_PREFIX = "SETTLEMENT_GL_POSTING:";

    private final IdempotencyService idempotencyService;
    private final GLMappingResolver glMappingResolver;
    private final GLPostingService glPostingService;
    private final ProcessorSettlementRepository settlementRepository;
    private final ProcessorSettlementLineRepository settlementLineRepository;

    /**
     * Post the batched settlement journal entry for one settlement, idempotent on the settlement id.
     * Runs in the caller's transaction. Period-gate exceptions propagate unwrapped.
     *
     * @param settlementId the provider settlement (payout) id
     */
    public void postSettlement(@NonNull String settlementId) {
        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + settlementId;

        if (idempotencyService.isKeyProcessed(idempotencyKey)) {
            log.info("Settlement GL posting already processed, skipping | settlementId={}", settlementId);
            return;
        }

        ProcessorSettlement settlement =
                settlementRepository.findById(settlementId).orElse(null);
        if (settlement == null) {
            log.warn("Settlement not found for GL posting, skipping | settlementId={}", settlementId);
            return;
        }
        if (settlement.getStatus() == SettlementStatus.POSTED) {
            log.info("Settlement already POSTED, skipping | settlementId={}", settlementId);
            return;
        }

        // Transaction date = the provider settlement date (business date), not clock time, so outbox
        // retries post into the correct period and select the correct effective-dated GL mapping.
        LocalDateTime transactionDate = LocalDateTime.of(settlement.getSettlementDate(), LocalTime.MIDNIGHT);

        List<ProcessorSettlementLine> lines = settlementLineRepository.findBySettlementId(settlementId);
        BigDecimal matchedGross = lines.stream()
                .filter(l -> l.getMatchStatus() == SettlementLineMatchStatus.MATCHED)
                .map(ProcessorSettlementLine::getGrossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unmatchedGross = settlement.getGrossAmount().subtract(matchedGross);

        UUID cashAccountId =
                glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, CASH_MAPPING_KEY, transactionDate);
        UUID feesAccountId =
                glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, FEES_MAPPING_KEY, transactionDate);
        UUID undepositedAccountId =
                glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, UNDEPOSITED_MAPPING_KEY, transactionDate);
        UUID suspenseAccountId =
                glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, SUSPENSE_MAPPING_KEY, transactionDate);

        UUID sourceEventId = toSourceEventId(settlementId);
        String description = "Processor settlement " + settlementId + " (" + settlement.getProviderCode() + ")";

        SettlementPostingCommand command = new SettlementPostingCommand(
                sourceEventId,
                cashAccountId,
                feesAccountId,
                undepositedAccountId,
                suspenseAccountId,
                settlement.getNetAmount(),
                settlement.getFeeAmount(),
                matchedGross,
                unmatchedGross,
                transactionDate,
                description,
                null);

        JournalEntry posted = glPostingService.postSettlement(command);

        settlement.setStatus(SettlementStatus.POSTED);
        settlement.setMatchedGross(matchedGross);
        settlement.setJournalEntryId(posted.getJournalEntryId());
        settlementRepository.save(settlement);

        idempotencyService.registerKey(idempotencyKey, posted.getJournalEntryId());

        log.info(
                "Settlement GL posting completed | settlementId={} | journalEntryId={} | matchedGross={} "
                        + "| unmatchedGross={}",
                settlementId,
                posted.getJournalEntryId(),
                matchedGross,
                unmatchedGross);
    }

    /**
     * Derive the journal entry {@code sourceEventId} deterministically from the provider settlement
     * id so replays always produce the same source event id.
     */
    static @NonNull UUID toSourceEventId(@NonNull String settlementId) {
        try {
            return UUID.fromString(settlementId);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(("settlement:" + settlementId).getBytes(StandardCharsets.UTF_8));
        }
    }
}
