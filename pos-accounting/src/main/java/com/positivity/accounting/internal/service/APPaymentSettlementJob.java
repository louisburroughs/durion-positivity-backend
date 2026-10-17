package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.ReconcileAPPaymentGatewayRequest;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.enums.APPaymentSettlementStatus;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.payment.GatewayPaymentResponse;
import com.positivity.accounting.internal.payment.PaymentGatewayException;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.tenancy.TenantIterator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The settlement signal (#2642 ruling 1): a scheduled re-read of the gateway, per tenant, in two sweeps.
 *
 * <ul>
 *   <li><b>{@code GATEWAY_PENDING} orphans</b> older than {@code accounting.ap.settlement.orphan-grace}: the gateway is
 *       asked by reference ({@code findByReference(paymentRef)}, the pay command's idempotency key). A charge it holds
 *       drives the {@code reconcile-gateway} CHARGED path (posted as an unapplied payment); a FAILED or DECLINED charge
 *       marks the payment {@code GATEWAY_FAILED}; no charge at all, once the gateway's idempotency window has passed,
 *       drives the NOT_CHARGED path ({@code VOIDED}); inside the window a not-found is left alone (a resend may still
 *       replay it).
 *   <li><b>In-flight charges</b> ({@code settlementStatus = IN_FLIGHT} on a payment that posted or is posting): the
 *       gateway is asked by transaction id. SUCCEEDED → {@code SETTLED} (no entry, audit {@code AP_PAYMENT_SETTLE});
 *       FAILED or DECLINED → the return ({@link APPaymentReturnService#returnFromGateway}); PENDING / AUTHORIZED →
 *       {@code settlementCheckedAt} only; a charge the gateway no longer finds is logged and left in flight.
 * </ul>
 *
 * {@code SETTLED} rows are never re-read: a late return reaches the books through the bank statement and the operator's
 * {@code record-return}. Every payment is handled in a transaction of its own under its row lock; one failure never
 * stops the sweep. Clock injected (ADR-0024). A signed webhook, when one exists, only triggers this re-read.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "accounting.ap.settlement",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class APPaymentSettlementJob {

    static final String SYSTEM = "system";
    static final String SETTLE_OPERATION = "AP_PAYMENT_SETTLE";
    private static final List<APPaymentStatus> POSTING_STATES =
            List.of(APPaymentStatus.GL_POST_PENDING, APPaymentStatus.GL_POSTED, APPaymentStatus.GL_POST_FAILED);

    private final APPaymentRepository payments;
    private final APPaymentService paymentService;
    private final APPaymentReturnService returns;
    private final APPaymentFailurePersistenceService failures;
    private final AccountingAuditLogRepository auditLogs;
    private final ObjectProvider<PaymentGatewayProvider> gateway;
    private final TenantIterator tenantIterator;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration orphanGrace;
    private final Duration idempotencyWindow;
    private final int batchSize;

    public APPaymentSettlementJob(
            APPaymentRepository payments,
            APPaymentService paymentService,
            APPaymentReturnService returns,
            APPaymentFailurePersistenceService failures,
            AccountingAuditLogRepository auditLogs,
            ObjectProvider<PaymentGatewayProvider> gateway,
            TenantIterator tenantIterator,
            AccountingCalendarZoneResolver zoneResolver,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${accounting.ap.settlement.orphan-grace:PT15M}") Duration orphanGrace,
            @Value("${stripe.idempotency-window-hours:24}") long idempotencyWindowHours,
            @Value("${accounting.ap.settlement.batch-size:200}") int batchSize) {
        this.payments = payments;
        this.paymentService = paymentService;
        this.returns = returns;
        this.failures = failures;
        this.auditLogs = auditLogs;
        this.gateway = gateway;
        this.tenantIterator = tenantIterator;
        this.zoneResolver = zoneResolver;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.orphanGrace = orphanGrace;
        this.idempotencyWindow = Duration.ofHours(idempotencyWindowHours);
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${accounting.ap.settlement.initial-delay:PT2M}",
            fixedDelayString = "${accounting.ap.settlement.poll-interval:PT15M}")
    public void reRead() {
        if (gateway.getIfAvailable() == null) {
            log.debug("AP settlement re-read: no payment gateway configured");
            return;
        }
        tenantIterator.forEachActiveTenant(tenantId -> {
            int handled = reReadBoundTenant();
            if (handled > 0) {
                log.info("AP settlement re-read: tenant={} payments={}", tenantId, handled);
            }
        });
    }

    /**
     * One tenant's sweep, with the tenant already bound.
     *
     * @return how many payments changed state
     */
    public int reReadBoundTenant() {
        PaymentGatewayProvider provider = gateway.getObject();
        Instant now = Instant.now(clock);
        int changed = 0;
        List<UUID> orphans = transaction.execute(
                status -> payments.findOrphanIds(now.minus(orphanGrace), PageRequest.of(0, batchSize)));
        for (UUID paymentId : orphans == null ? List.<UUID>of() : orphans) {
            changed += guarded(paymentId, () -> reconcileOrphan(provider, paymentId, now)) ? 1 : 0;
        }
        List<UUID> inFlight =
                transaction.execute(status -> payments.findInFlightIds(POSTING_STATES, PageRequest.of(0, batchSize)));
        for (UUID paymentId : inFlight == null ? List.<UUID>of() : inFlight) {
            changed += guarded(paymentId, () -> checkInFlight(provider, paymentId, now)) ? 1 : 0;
        }
        return changed;
    }

    private boolean guarded(UUID paymentId, java.util.function.BooleanSupplier step) {
        try {
            return step.getAsBoolean();
        } catch (PaymentGatewayException e) {
            log.warn("AP settlement re-read: gateway unavailable for payment {}: {}", paymentId, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.error("AP settlement re-read failed for payment {}", paymentId, e);
            return false;
        }
    }

    /** A GATEWAY_PENDING orphan: what does the gateway hold under its reference? */
    private boolean reconcileOrphan(PaymentGatewayProvider provider, UUID paymentId, Instant now) {
        APPayment payment = payments.findById(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != APPaymentStatus.GATEWAY_PENDING) {
            return false;
        }
        Optional<GatewayPaymentResponse> answer = provider.findByReference(payment.getPaymentRef());
        if (answer.isEmpty()) {
            if (payment.getCreatedAt() != null
                    && payment.getCreatedAt().plus(idempotencyWindow).isBefore(now)) {
                // Nothing at the gateway and the window in which a resend could replay it has passed: never charged.
                paymentService.reconcileGateway(
                        paymentId,
                        ReconcileAPPaymentGatewayRequest.builder()
                                .gatewayOutcome(ReconcileAPPaymentGatewayRequest.GatewayOutcome.NOT_CHARGED)
                                .justification("GATEWAY_LOOKUP: no charge under this reference after the idempotency"
                                        + " window (" + idempotencyWindow + ")")
                                .build(),
                        SYSTEM);
                return true;
            }
            return false;
        }
        GatewayPaymentResponse charge = answer.get();
        switch (charge.getStatus()) {
            case SUCCEEDED, PENDING, AUTHORIZED -> {
                paymentService.reconcileGateway(
                        paymentId,
                        ReconcileAPPaymentGatewayRequest.builder()
                                .gatewayOutcome(ReconcileAPPaymentGatewayRequest.GatewayOutcome.CHARGED)
                                .gatewayTransactionId(charge.getTransactionId())
                                .justification("GATEWAY_LOOKUP: the gateway holds the charge as " + charge.getStatus())
                                .build(),
                        SYSTEM);
                if (charge.getStatus() == PaymentGatewayProvider.GatewayPaymentStatus.SUCCEEDED) {
                    transaction.executeWithoutResult(
                            status -> payments.lockById(paymentId).ifPresent(locked -> {
                                locked.setSettlementStatus(APPaymentSettlementStatus.SETTLED);
                                locked.setSettledAt(now);
                                locked.setSettlementCheckedAt(now);
                                payments.save(locked);
                            }));
                }
                return true;
            }
            case FAILED, DECLINED -> {
                failures.persistGatewayFailure(
                        paymentId,
                        "GATEWAY_LOOKUP: " + charge.getStatus()
                                + (charge.getFailureCode() == null ? "" : " " + charge.getFailureCode()));
                return true;
            }
        }
        return false;
    }

    /** A charge in flight: settled, returned, or still pending? */
    private boolean checkInFlight(PaymentGatewayProvider provider, UUID paymentId, Instant now) {
        APPayment payment = payments.findById(paymentId).orElse(null);
        if (payment == null
                || payment.getSettlementStatus() != APPaymentSettlementStatus.IN_FLIGHT
                || payment.getGatewayTransactionId() == null) {
            return false;
        }
        Optional<GatewayPaymentResponse> answer = provider.getPaymentStatus(payment.getGatewayTransactionId());
        if (answer.isEmpty()) {
            log.warn(
                    "AP settlement re-read: the gateway no longer finds charge of payment {}; left in flight",
                    payment.getPaymentRef());
            stamp(paymentId, now, null);
            return false;
        }
        GatewayPaymentResponse charge = answer.get();
        return switch (charge.getStatus()) {
            case SUCCEEDED -> {
                stamp(paymentId, now, now);
                yield true;
            }
            case FAILED, DECLINED -> {
                returnInFlight(paymentId, charge.getFailureCode());
                yield true;
            }
            case PENDING, AUTHORIZED -> {
                stamp(paymentId, now, null);
                yield false;
            }
        };
    }

    /** Settled (settledAt given) or merely checked. */
    private void stamp(UUID paymentId, Instant checkedAt, @Nullable Instant settledAt) {
        transaction.executeWithoutResult(status -> payments.lockById(paymentId).ifPresent(locked -> {
            locked.setSettlementCheckedAt(checkedAt);
            if (settledAt != null && locked.getSettlementStatus() == APPaymentSettlementStatus.IN_FLIGHT) {
                locked.setSettlementStatus(APPaymentSettlementStatus.SETTLED);
                locked.setSettledAt(settledAt);
                AccountingAuditLog row = new AccountingAuditLog();
                row.setEntityType("AP_PAYMENT");
                row.setEntityId(paymentId);
                row.setOperation(SETTLE_OPERATION);
                row.setUserId(SYSTEM);
                row.setOldValue(APPaymentSettlementStatus.IN_FLIGHT.name());
                row.setNewValue(APPaymentSettlementStatus.SETTLED.name() + ";settledAt=" + settledAt);
                auditLogs.save(row);
            }
            payments.save(locked);
        }));
    }

    /**
     * The return (ruling 2). The return service posts the mirror in its own transaction; a period refusal rolls that
     * back whole, and the payment is then held {@code RETURN_PENDING} with the refusal as {@code glPostError} (nothing
     * reopened while 2000 stays debited), for {@code gl-posting-retry} or the next sweep.
     */
    private void returnInFlight(UUID paymentId, @Nullable String failureCode) {
        LocalDate today = zoneResolver.today();
        try {
            transaction.executeWithoutResult(status -> payments.lockById(paymentId)
                    .filter(locked -> locked.getSettlementStatus() == APPaymentSettlementStatus.IN_FLIGHT)
                    .ifPresent(locked -> returns.returnFromGateway(locked, failureCode, today)));
        } catch (RuntimeException e) {
            Optional<String> refusal = APPaymentPostingService.refusalCode(e);
            if (refusal.isEmpty()) {
                throw e;
            }
            log.warn(
                    "AP payment {} returned but its mirror was refused ({}); held RETURN_PENDING",
                    paymentId,
                    refusal.get());
            returns.holdReturn(paymentId, failureCode, today, refusal.get());
        }
    }

    @NonNull
    Duration orphanGrace() {
        return orphanGrace;
    }
}
