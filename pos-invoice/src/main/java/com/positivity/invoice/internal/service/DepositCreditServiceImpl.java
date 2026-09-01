package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.config.PaymentEventPublisher;
import com.positivity.invoice.internal.dto.DepositCreditSummary;
import com.positivity.invoice.internal.entity.DepositCredit;
import com.positivity.invoice.internal.entity.DepositCreditApplication;
import com.positivity.invoice.internal.enums.DepositCreditStatus;
import com.positivity.invoice.internal.enums.DepositSourceType;
import com.positivity.invoice.internal.exception.DepositCreditNotFoundException;
import com.positivity.invoice.internal.repository.DepositCreditApplicationRepository;
import com.positivity.invoice.internal.repository.DepositCreditRepository;
import com.positivity.invoice.internal.service.model.CreateDepositCommand;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deposit / down-payment credits (odoo-parity story E4, issue #1085, spec R7.4). See
 * {@link DepositCreditService} for the contract. Credits are drawn down FIFO against settlement
 * invoices for the same source document, each draw-down recorded as an application-audit row so the
 * provenance chain (deposit-take order → credit → each settlement invoice) is fully recoverable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositCreditServiceImpl implements DepositCreditService {

    private static final List<DepositCreditStatus> DRAWABLE_STATUSES =
            List.of(DepositCreditStatus.AVAILABLE, DepositCreditStatus.PARTIALLY_APPLIED);

    private final DepositCreditRepository depositCreditRepository;
    private final DepositCreditApplicationRepository depositCreditApplicationRepository;
    private final PaymentEventPublisher paymentEventPublisher;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull DepositCreditSummary createDeposit(@NonNull CreateDepositCommand command) {
        DepositCredit replayed =
                depositCreditRepository.findByOrderId(command.orderId()).orElse(null);
        if (replayed != null) {
            return toSummary(replayed);
        }
        if (command.amount().signum() <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }
        DepositSourceType sourceType = parseSourceType(command.sourceType());
        BigDecimal amount = scale(command.amount());
        String currency =
                command.currencyCode() == null || command.currencyCode().isBlank()
                        ? "USD"
                        : command.currencyCode().trim().toUpperCase(Locale.ROOT);

        DepositCredit credit = DepositCredit.builder()
                .sourceType(sourceType)
                .sourceId(command.sourceId())
                .orderId(command.orderId())
                .partyId(command.partyId())
                .currencyCode(currency)
                .originalAmount(amount)
                .remainingBalance(amount)
                .status(DepositCreditStatus.AVAILABLE)
                .build();
        DepositCredit saved = depositCreditRepository.save(credit);
        log.info(
                "Registered deposit credit {} of {} against {} {} (order {})",
                saved.getDepositCreditId(),
                amount,
                sourceType,
                command.sourceId(),
                command.orderId());
        return toSummary(saved);
    }

    @Override
    @Transactional
    public @NonNull BigDecimal applyAvailableCredits(
            @NonNull DepositSourceType sourceType,
            @NonNull UUID sourceId,
            @NonNull UUID invoiceId,
            @NonNull BigDecimal invoiceTotal) {
        BigDecimal remaining = scale(invoiceTotal);
        if (remaining.signum() <= 0) {
            return zero();
        }
        BigDecimal appliedTotal = zero();
        List<DepositCredit> credits = depositCreditRepository.findBySourceTypeAndSourceIdAndStatusInOrderByCreatedAtAsc(
                sourceType, sourceId, DRAWABLE_STATUSES);
        // One query for the credits already applied to this invoice — no per-credit lazy load of
        // the applications collection (Copilot #1093 review: avoids N+1).
        Set<UUID> alreadyAppliedToInvoice =
                depositCreditApplicationRepository.findDepositCreditIdsByInvoiceId(invoiceId);
        for (DepositCredit credit : credits) {
            if (remaining.signum() <= 0) {
                break;
            }
            // Idempotent per (credit, invoice): a credit already applied to this invoice is skipped.
            if (alreadyAppliedToInvoice.contains(credit.getDepositCreditId())) {
                continue;
            }
            BigDecimal drawable = credit.getRemainingBalance().min(remaining);
            if (drawable.signum() <= 0) {
                continue;
            }
            credit.addApplication(DepositCreditApplication.builder()
                    .invoiceId(invoiceId)
                    .amountApplied(drawable)
                    .build());
            BigDecimal newBalance = scale(credit.getRemainingBalance().subtract(drawable));
            credit.setRemainingBalance(newBalance);
            credit.setStatus(
                    newBalance.signum() == 0
                            ? DepositCreditStatus.FULLY_APPLIED
                            : DepositCreditStatus.PARTIALLY_APPLIED);
            depositCreditRepository.save(credit);
            paymentEventPublisher.publishDepositCreditApplied(credit, invoiceId, drawable);

            appliedTotal = appliedTotal.add(drawable);
            remaining = scale(remaining.subtract(drawable));
        }
        if (appliedTotal.signum() > 0) {
            log.info(
                    "Applied {} of deposit credit to invoice {} for {} {}",
                    appliedTotal,
                    invoiceId,
                    sourceType,
                    sourceId);
        }
        return scale(appliedTotal);
    }

    @Override
    @Transactional
    public @NonNull BigDecimal refundDeposit(@NonNull UUID depositCreditId, @NonNull String reason) {
        DepositCredit credit = require(depositCreditId);
        if (credit.getStatus() == DepositCreditStatus.REFUNDED) {
            return zero();
        }
        BigDecimal refunded = scale(credit.getRemainingBalance());
        credit.setRemainingBalance(zero());
        credit.setStatus(DepositCreditStatus.REFUNDED);
        depositCreditRepository.save(credit);
        log.info("Refunded {} of deposit credit {} ({})", refunded, depositCreditId, reason);
        return refunded;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull DepositCreditSummary getDeposit(@NonNull UUID depositCreditId) {
        return toSummary(require(depositCreditId));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<DepositCreditSummary> listBySource(
            @NonNull DepositSourceType sourceType, @NonNull UUID sourceId) {
        return depositCreditRepository.findBySourceTypeAndSourceId(sourceType, sourceId).stream()
                .map(DepositCreditServiceImpl::toSummary)
                .toList();
    }

    private DepositCredit require(UUID depositCreditId) {
        return depositCreditRepository
                .findById(depositCreditId)
                .orElseThrow(() -> new DepositCreditNotFoundException(depositCreditId));
    }

    private static DepositSourceType parseSourceType(String raw) {
        try {
            return DepositSourceType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown deposit source type: " + raw);
        }
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }

    private static DepositCreditSummary toSummary(DepositCredit c) {
        List<DepositCreditSummary.Application> applications = c.getApplications().stream()
                .map(a ->
                        new DepositCreditSummary.Application(a.getInvoiceId(), a.getAmountApplied(), a.getAppliedAt()))
                .toList();
        return new DepositCreditSummary(
                c.getDepositCreditId(),
                c.getSourceType().name(),
                c.getSourceId(),
                c.getOrderId(),
                c.getPartyId(),
                c.getCurrencyCode(),
                c.getOriginalAmount(),
                c.getRemainingBalance(),
                c.getStatus().name(),
                applications,
                c.getCreatedAt());
    }
}
