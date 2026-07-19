package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.SettlementGLPostingEvent;
import com.positivity.accounting.internal.entity.ExtPaymentSettlementConfig;
import com.positivity.accounting.internal.entity.ProcessorSettlement;
import com.positivity.accounting.internal.entity.ProcessorSettlementLine;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.enums.MatchReferenceField;
import com.positivity.accounting.internal.enums.MatchedPaymentType;
import com.positivity.accounting.internal.enums.SettlementLineMatchStatus;
import com.positivity.accounting.internal.enums.SettlementLineType;
import com.positivity.accounting.internal.enums.SettlementStatus;
import com.positivity.accounting.internal.repository.ExtPaymentSettlementConfigRepository;
import com.positivity.accounting.internal.repository.ProcessorSettlementLineRepository;
import com.positivity.accounting.internal.repository.ProcessorSettlementRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.service.GLMappingResolver;
import com.positivity.accounting.service.GLPostingService;
import com.positivity.accounting.service.OutboxService;
import com.positivity.accounting.service.SettlementReconciliationService;
import com.positivity.domainevents.payment.SettlementReportedV1;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Processor settlement reconciliation (story F1c, issue #963, decisions D-9…D-14).
 *
 * <p>v1 matching scope: only {@code CHARGE} lines are auto-matched, to platform {@code
 * ReceivablePayment}s on gross within the provider's tolerance (their gross clears Undeposited
 * Funds, closing the C1 loop). Every other line type — REFUND, CHARGEBACK, FEE, ADJUSTMENT,
 * RESERVE_*, PAYOUT_CORRECTION — is left {@code UNMATCHED} for the manual review workflow so nothing
 * is mis-posted (decision D-10). AP/refund auto-matching is a recorded backlog item.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SettlementReconciliationServiceImpl implements SettlementReconciliationService {

    static final String SETTLEMENT_CATEGORY = "SETTLEMENT";
    static final String UNDEPOSITED_MAPPING_KEY = "UNDEPOSITED_FUNDS";
    static final String SUSPENSE_MAPPING_KEY = "SETTLEMENT_SUSPENSE";
    static final String ADJUSTMENT_CATEGORY = "SETTLEMENT_ADJUSTMENT";
    static final String ADJUSTMENT_MAPPING_KEY = "SETTLEMENT_ADJUSTMENT";

    private final Clock clock;
    private final ProcessorSettlementRepository settlementRepository;
    private final ProcessorSettlementLineRepository lineRepository;
    private final ExtPaymentSettlementConfigRepository configRepository;
    private final ReceivablePaymentRepository receivablePaymentRepository;
    private final OutboxService outboxService;
    private final GLMappingResolver glMappingResolver;
    private final GLPostingService glPostingService;

    /** Write-off self-service ceiling (decision D-14, flagged for Finance ratification). */
    @Value("${accounting.reconciliation.writeoff.threshold:25.00}")
    private BigDecimal writeOffThreshold;

    @Override
    public void ingestSettlement(@NonNull SettlementReportedV1 payload) {
        if (settlementRepository.existsByEventId(payload.eventId())
                || settlementRepository.existsById(payload.settlementId())) {
            log.info(
                    "Settlement already ingested, skipping | settlementId={} | eventId={}",
                    payload.settlementId(),
                    payload.eventId());
            return;
        }

        ExtPaymentSettlementConfig config =
                configRepository.findById(payload.providerCode()).orElse(null);

        ProcessorSettlement settlement = ProcessorSettlement.builder()
                .settlementId(payload.settlementId())
                .eventId(payload.eventId())
                .providerCode(payload.providerCode())
                .settlementDate(payload.settlementDate())
                .currency(payload.currency())
                .grossAmount(payload.grossAmount())
                .feeAmount(payload.feeAmount())
                .netAmount(payload.netAmount())
                .status(SettlementStatus.RECEIVED)
                .matchedGross(BigDecimal.ZERO)
                .lineCount(payload.lines().size())
                .unmatchedCount(0)
                .build();
        settlementRepository.save(settlement);

        BigDecimal matchedGross = BigDecimal.ZERO;
        int unmatchedCount = 0;
        for (SettlementReportedV1.Line contractLine : payload.lines()) {
            ProcessorSettlementLine line = toLine(payload.settlementId(), contractLine);
            ReceivablePayment matched = matchCharge(contractLine, config);
            if (matched != null) {
                line.setMatchStatus(SettlementLineMatchStatus.MATCHED);
                line.setMatchedPaymentType(MatchedPaymentType.AR);
                line.setMatchedPaymentId(matched.getPaymentId());
                matchedGross = matchedGross.add(contractLine.grossAmount());
            } else {
                line.setMatchStatus(SettlementLineMatchStatus.UNMATCHED);
                unmatchedCount++;
            }
            lineRepository.save(line);
        }

        settlement.setMatchedGross(matchedGross);
        settlement.setUnmatchedCount(unmatchedCount);
        settlementRepository.save(settlement);

        enqueuePosting(settlement);

        log.info(
                "Ingested settlement | settlementId={} | provider={} | lines={} | matchedGross={} | unmatched={}",
                payload.settlementId(),
                payload.providerCode(),
                payload.lines().size(),
                matchedGross,
                unmatchedCount);
    }

    /**
     * Attempt to match a CHARGE line to a receivable payment on gross within tolerance (decisions
     * D-11/D-12). Returns null for non-CHARGE lines or when no payment matches.
     */
    private ReceivablePayment matchCharge(SettlementReportedV1.@NonNull Line line, ExtPaymentSettlementConfig config) {
        if (line.lineType() != SettlementReportedV1.LineType.CHARGE) {
            return null;
        }
        MatchReferenceField refField =
                config != null ? config.getMatchReferenceField() : MatchReferenceField.PAYMENT_REFERENCE;
        String ref =
                refField == MatchReferenceField.PROVIDER_LINE_REF ? line.providerLineRef() : line.paymentReference();
        if (ref == null || ref.isBlank()) {
            return null;
        }
        UUID sourceEventId;
        try {
            sourceEventId = UUID.fromString(ref);
        } catch (IllegalArgumentException e) {
            log.debug("CHARGE line reference not a UUID, cannot match | ref={}", ref);
            return null;
        }
        ReceivablePayment payment =
                receivablePaymentRepository.findBySourceEventId(sourceEventId).orElse(null);
        if (payment == null) {
            return null;
        }
        BigDecimal tolerance = config != null ? config.getAmountTolerance() : BigDecimal.ZERO;
        if (payment.getTotalAmount().subtract(line.grossAmount()).abs().compareTo(tolerance) > 0) {
            log.debug(
                    "CHARGE line gross {} outside tolerance {} of payment total {} | ref={}",
                    line.grossAmount(),
                    tolerance,
                    payment.getTotalAmount(),
                    ref);
            return null;
        }
        return payment;
    }

    private ProcessorSettlementLine toLine(
            @NonNull String settlementId, SettlementReportedV1.@NonNull Line contractLine) {
        return ProcessorSettlementLine.builder()
                .lineId(UUIDv7Generator.generate())
                .settlementId(settlementId)
                .providerLineRef(contractLine.providerLineRef())
                .lineType(SettlementLineType.valueOf(contractLine.lineType().name()))
                .grossAmount(contractLine.grossAmount())
                .feeAmount(contractLine.feeAmount())
                .netAmount(contractLine.netAmount())
                .paymentReference(contractLine.paymentReference())
                .build();
    }

    private void enqueuePosting(@NonNull ProcessorSettlement settlement) {
        SettlementGLPostingEvent event = SettlementGLPostingEvent.builder()
                .eventId(UUIDv7Generator.generate())
                .settlementId(settlement.getSettlementId())
                .providerCode(settlement.getProviderCode())
                .settlementDate(settlement.getSettlementDate())
                .build();
        outboxService.saveToOutbox(
                event.getEventId(),
                "ProcessorSettlement",
                settlement.getEventId(),
                event.getClass().getName(),
                event);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcessorSettlementLine> listLines(@NonNull String settlementId, boolean unmatchedOnly) {
        return unmatchedOnly
                ? lineRepository.findBySettlementIdAndMatchStatus(settlementId, SettlementLineMatchStatus.UNMATCHED)
                : lineRepository.findBySettlementId(settlementId);
    }

    @Override
    public ProcessorSettlementLine manualMatchToReceivable(@NonNull UUID lineId, @NonNull UUID receivablePaymentId) {
        ProcessorSettlementLine line = requireUnmatchedLine(lineId);
        ReceivablePayment payment = receivablePaymentRepository
                .findById(receivablePaymentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Receivable payment not found: " + receivablePaymentId));

        LocalDateTime txDate = txDateOf(line);
        UUID suspenseAccount = glMappingResolver.resolveGLAccount(SETTLEMENT_CATEGORY, SUSPENSE_MAPPING_KEY, txDate);
        UUID undepositedAccount =
                glMappingResolver.resolveGLAccount(SETTLEMENT_CATEGORY, UNDEPOSITED_MAPPING_KEY, txDate);

        UUID sourceEventId = deterministicId("settlement-reclass:" + lineId);
        glPostingService.postSettlementReclass(
                sourceEventId,
                suspenseAccount,
                undepositedAccount,
                line.getGrossAmount(),
                txDate,
                "Manual match of settlement line " + lineId + " to receivable payment " + receivablePaymentId,
                null);

        line.setMatchStatus(SettlementLineMatchStatus.MATCHED);
        line.setMatchedPaymentType(MatchedPaymentType.AR);
        line.setMatchedPaymentId(payment.getPaymentId());
        ProcessorSettlementLine saved = lineRepository.save(line);

        adjustSettlementAfterMatch(line.getSettlementId(), line.getGrossAmount());
        log.info("Manually matched settlement line {} to receivable payment {}", lineId, receivablePaymentId);
        return saved;
    }

    @Override
    public ProcessorSettlementLine writeOffLine(@NonNull UUID lineId, @NonNull String reason) {
        if (reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Write-off reason is required");
        }
        ProcessorSettlementLine line = requireUnmatchedLine(lineId);
        if (line.getGrossAmount().compareTo(writeOffThreshold) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Write-off amount " + line.getGrossAmount() + " exceeds threshold " + writeOffThreshold
                            + "; manual match or escalate");
        }

        LocalDateTime txDate = txDateOf(line);
        UUID suspenseAccount = glMappingResolver.resolveGLAccount(SETTLEMENT_CATEGORY, SUSPENSE_MAPPING_KEY, txDate);
        UUID adjustmentAccount =
                glMappingResolver.resolveGLAccount(ADJUSTMENT_CATEGORY, ADJUSTMENT_MAPPING_KEY, txDate);

        UUID sourceEventId = deterministicId("settlement-writeoff:" + lineId);
        var posted = glPostingService.postSettlementWriteOff(
                sourceEventId,
                suspenseAccount,
                adjustmentAccount,
                line.getGrossAmount(),
                txDate,
                "Write-off of settlement line " + lineId + ": " + reason,
                null);

        line.setMatchStatus(SettlementLineMatchStatus.WRITTEN_OFF);
        line.setWriteoffReason(reason);
        line.setWriteoffJournalEntryId(posted.getJournalEntryId());
        ProcessorSettlementLine saved = lineRepository.save(line);

        adjustSettlementAfterMatch(line.getSettlementId(), null);
        log.info("Wrote off settlement line {} (amount {}): {}", lineId, line.getGrossAmount(), reason);
        return saved;
    }

    private ProcessorSettlementLine requireUnmatchedLine(@NonNull UUID lineId) {
        ProcessorSettlementLine line = lineRepository
                .findById(lineId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Settlement line not found: " + lineId));
        if (line.getMatchStatus() != SettlementLineMatchStatus.UNMATCHED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Settlement line " + lineId + " is not UNMATCHED (" + line.getMatchStatus() + ")");
        }
        return line;
    }

    /**
     * After a manual match or write-off, decrement the parent's unmatched count and (for a match)
     * add the line gross to matched gross. Note the reclass/write-off JE moves the money out of
     * suspense; the original batched settlement JE is not retro-edited (reverse-never-edit).
     */
    private void adjustSettlementAfterMatch(@NonNull String settlementId, BigDecimal matchedDelta) {
        settlementRepository.findById(settlementId).ifPresent(s -> {
            if (matchedDelta != null) {
                s.setMatchedGross(s.getMatchedGross().add(matchedDelta));
            }
            s.setUnmatchedCount(Math.max(0, s.getUnmatchedCount() - 1));
            settlementRepository.save(s);
        });
    }

    private LocalDateTime txDateOf(@NonNull ProcessorSettlementLine line) {
        return settlementRepository
                .findById(line.getSettlementId())
                .map(s -> LocalDateTime.of(s.getSettlementDate(), LocalTime.MIDNIGHT))
                .orElseGet(() -> LocalDateTime.now(clock));
    }

    private static UUID deterministicId(@NonNull String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
