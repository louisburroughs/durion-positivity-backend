package com.positivity.order.internal.service;

import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.entity.CashMovement;
import com.positivity.order.internal.entity.CashMovementType;
import com.positivity.order.internal.entity.OrderPaymentRecord;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.exception.RegisterSessionNotFoundException;
import com.positivity.order.internal.exception.SessionCloseBlockedException;
import com.positivity.order.internal.repository.CashMovementRepository;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.service.RegisterSessionService;
import com.positivity.order.service.model.CashMovementCommand;
import com.positivity.order.service.model.CashMovementSummary;
import com.positivity.order.service.model.OpenSessionCommand;
import com.positivity.order.service.model.RegisterSessionSummary;
import com.positivity.order.service.model.SessionReport;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Register (drawer) sessions and cash management (parity stories G1/G2, spec R6.1–R6.6). The Odoo
 * {@code pos.session} + cash-control analog: a session is a drawer shift on one terminal, orders
 * tendered while it is OPEN bind to it, and close reconciles the counted drawer against the
 * theoretical cash (opening float + Σ session CASH settlements + Σ cash movements).
 */
@Service
@RequiredArgsConstructor
public class RegisterSessionServiceImpl implements RegisterSessionService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final String CASH = "CASH";
    private static final String CURRENCY = "USD";

    private final RegisterSessionRepository registerSessionRepository;
    private final CashMovementRepository cashMovementRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderPaymentRecordRepository paymentRecordRepository;
    private final OrderDomainEventPublisher domainEventPublisher;
    private final Clock clock;

    /** Over/short beyond this absolute amount at close requires order:session:approve_variance. */
    @Value("${pos.order.session.authorized-difference-limit:5.00}")
    private BigDecimal authorizedDifferenceLimit;

    @Override
    @Transactional
    public @NonNull RegisterSessionSummary openSession(@NonNull OpenSessionCommand command) {
        registerSessionRepository
                .findFirstByTerminalIdAndStatus(command.terminalId(), RegisterSessionStatus.OPEN)
                .ifPresent(existing -> {
                    throw new RegisterSessionConflictException(
                            "Terminal " + command.terminalId() + " already has an open register session");
                });

        BigDecimal openingFloat = command.openingFloat() != null
                ? scale(command.openingFloat())
                : previousCountedClose(command.terminalId());
        UUID locationId = command.locationId() != null ? command.locationId() : previousLocation(command.terminalId());

        Instant now = Instant.now(clock);
        RegisterSession session = RegisterSession.builder()
                .terminalId(command.terminalId())
                .locationId(locationId)
                .openedByClerkId(command.openedByClerkId())
                .status(RegisterSessionStatus.OPEN)
                .openingFloat(openingFloat)
                .openedAt(now)
                .build();
        return toSummary(registerSessionRepository.save(session));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<RegisterSessionSummary> currentSessionForTerminal(@NonNull String terminalId) {
        Optional<RegisterSession> open =
                registerSessionRepository.findFirstByTerminalIdAndStatus(terminalId, RegisterSessionStatus.OPEN);
        if (open.isPresent()) {
            return open.map(this::toSummary);
        }
        return registerSessionRepository
                .findFirstByTerminalIdAndStatus(terminalId, RegisterSessionStatus.CLOSING)
                .map(this::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull RegisterSessionSummary getSession(@NonNull UUID sessionId) {
        return toSummary(require(sessionId));
    }

    @Override
    @Transactional
    public @NonNull CashMovementSummary recordCashMovement(@NonNull CashMovementCommand command) {
        RegisterSession session = require(command.sessionId());
        if (session.getStatus() != RegisterSessionStatus.OPEN) {
            throw new RegisterSessionConflictException("Cash movements require an OPEN session; session "
                    + session.getSessionId() + " is " + session.getStatus());
        }
        if (command.amount().signum() <= 0) {
            throw new IllegalArgumentException("Cash movement amount must be positive");
        }
        CashMovementType type;
        try {
            type = CashMovementType.valueOf(command.movementType().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown cash movement type: " + command.movementType());
        }
        CashMovement movement = CashMovement.builder()
                .sessionId(session.getSessionId())
                .movementType(type)
                .amount(scale(command.amount()))
                .reason(command.reason())
                .clerkId(command.clerkId())
                .occurredAt(Instant.now(clock))
                .build();
        return toMovementSummary(cashMovementRepository.save(movement));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<CashMovementSummary> listCashMovements(@NonNull UUID sessionId) {
        require(sessionId);
        return cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(sessionId).stream()
                .map(RegisterSessionServiceImpl::toMovementSummary)
                .toList();
    }

    @Override
    @Transactional
    public @NonNull RegisterSessionSummary beginClose(@NonNull UUID sessionId, @NonNull BigDecimal countedCash) {
        RegisterSession session = require(sessionId);
        if (session.getStatus() == RegisterSessionStatus.CLOSED) {
            throw new RegisterSessionConflictException("Session " + sessionId + " is already closed");
        }
        if (salesOrderRepository.existsBySessionIdAndStatus(sessionId, SalesOrderStatus.PENDING_PAYMENT)) {
            throw new SessionCloseBlockedException(sessionId);
        }
        session.setCountedCash(scale(countedCash));
        session.setStatus(RegisterSessionStatus.CLOSING);
        session.setClosingStartedAt(Instant.now(clock));
        return toSummary(registerSessionRepository.save(session));
    }

    @Override
    @Transactional
    public @NonNull RegisterSessionSummary confirmClose(@NonNull UUID sessionId) {
        RegisterSession session = require(sessionId);
        if (session.getStatus() != RegisterSessionStatus.CLOSING) {
            throw new RegisterSessionConflictException(
                    "Confirm-close requires a session in CLOSING (call begin-close first); session " + sessionId
                            + " is " + session.getStatus());
        }
        // Re-check R6.2: an order could have re-entered PENDING_PAYMENT since begin-close.
        if (salesOrderRepository.existsBySessionIdAndStatus(sessionId, SalesOrderStatus.PENDING_PAYMENT)) {
            throw new SessionCloseBlockedException(sessionId);
        }
        BigDecimal counted = session.getCountedCash() != null ? session.getCountedCash() : ZERO;
        BigDecimal cashMovementTotal = cashMovementTotal(sessionId);
        BigDecimal cashSettlements = cashSettlements(sessionId);
        BigDecimal theoretical =
                scale(session.getOpeningFloat().add(cashSettlements).add(cashMovementTotal));
        BigDecimal overShort = scale(counted.subtract(theoretical));

        if (overShort.abs().compareTo(authorizedDifferenceLimit) > 0) {
            if (!SecurityContextHelper.hasAuthority(OrderPermissions.ORDER_SESSION_APPROVE_VARIANCE)) {
                throw new AccessDeniedException("Register session over/short of " + overShort
                        + " exceeds the authorized difference limit of " + authorizedDifferenceLimit
                        + "; permission '" + OrderPermissions.ORDER_SESSION_APPROVE_VARIANCE + "' is required");
            }
            session.setVarianceApproved(true);
            session.setVarianceApprovedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        }

        Instant now = Instant.now(clock);
        session.setTheoreticalCash(theoretical);
        session.setOverShort(overShort);
        session.setStatus(RegisterSessionStatus.CLOSED);
        session.setClosedByClerkId(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        session.setClosedAt(now);
        RegisterSession saved = registerSessionRepository.save(session);

        List<RegisterSessionClosedV1.TenderTotal> tenderTotals = tenderTotals(sessionId).entrySet().stream()
                .map(e -> new RegisterSessionClosedV1.TenderTotal(e.getKey(), e.getValue()))
                .toList();
        domainEventPublisher.publishRegisterSessionClosed(
                saved,
                new RegisterSessionClosedV1(
                        saved.getSessionId(),
                        saved.getTerminalId(),
                        saved.getLocationId(),
                        saved.getOpenedByClerkId(),
                        saved.getClosedByClerkId(),
                        saved.getOpeningFloat(),
                        counted,
                        theoretical,
                        overShort,
                        saved.isVarianceApproved(),
                        CURRENCY,
                        tenderTotals,
                        cashMovementTotal,
                        saved.getOpenedAt(),
                        now));
        return toSummary(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull SessionReport xReport(@NonNull UUID sessionId) {
        return buildReport(require(sessionId), "X");
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull SessionReport zReport(@NonNull UUID sessionId) {
        return buildReport(require(sessionId), "Z");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SessionReport buildReport(RegisterSession session, String reportType) {
        UUID sessionId = session.getSessionId();
        BigDecimal cashMovementTotal = cashMovementTotal(sessionId);
        BigDecimal cashSettlements = cashSettlements(sessionId);
        BigDecimal theoretical =
                scale(session.getOpeningFloat().add(cashSettlements).add(cashMovementTotal));
        BigDecimal counted = session.getCountedCash();
        BigDecimal overShort = counted != null ? scale(counted.subtract(theoretical)) : null;
        List<SessionReport.TenderTotal> tenderTotals = tenderTotals(sessionId).entrySet().stream()
                .map(e -> new SessionReport.TenderTotal(e.getKey(), e.getValue()))
                .toList();
        List<CashMovementSummary> movements =
                cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(sessionId).stream()
                        .map(RegisterSessionServiceImpl::toMovementSummary)
                        .toList();
        long orderCount = salesOrderRepository.findBySessionId(sessionId).size();
        return new SessionReport(
                sessionId,
                session.getTerminalId(),
                session.getLocationId(),
                session.getStatus().name(),
                reportType,
                session.getOpeningFloat(),
                tenderTotals,
                cashSettlements,
                cashMovementTotal,
                theoretical,
                counted,
                overShort,
                orderCount,
                movements,
                session.getOpenedAt(),
                Instant.now(clock));
    }

    private RegisterSession require(UUID sessionId) {
        return registerSessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new RegisterSessionNotFoundException(sessionId));
    }

    /** Opening float defaults to the previous session's counted close (R6.1), else zero. */
    private BigDecimal previousCountedClose(String terminalId) {
        return registerSessionRepository
                .findFirstByTerminalIdOrderByOpenedAtDesc(terminalId)
                .map(RegisterSession::getCountedCash)
                .map(RegisterSessionServiceImpl::scale)
                .orElse(ZERO);
    }

    private UUID previousLocation(String terminalId) {
        return registerSessionRepository
                .findFirstByTerminalIdOrderByOpenedAtDesc(terminalId)
                .map(RegisterSession::getLocationId)
                .orElse(null);
    }

    /** Net CASH settled over the session: Σ SETTLED − Σ REVERSED. */
    private BigDecimal cashSettlements(UUID sessionId) {
        return paymentRecordRepository.findBySessionId(sessionId).stream()
                .filter(r -> CASH.equalsIgnoreCase(r.getMethodType()))
                .map(RegisterSessionServiceImpl::signedAmount)
                .reduce(ZERO, BigDecimal::add);
    }

    /** Net settled per tender method over the session (Σ SETTLED − Σ REVERSED), ordered by method. */
    private Map<String, BigDecimal> tenderTotals(UUID sessionId) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        for (OrderPaymentRecord record : paymentRecordRepository.findBySessionId(sessionId)) {
            String method = record.getMethodType() == null ? "OTHER" : record.getMethodType();
            totals.merge(method, signedAmount(record), BigDecimal::add);
        }
        Map<String, BigDecimal> scaled = new LinkedHashMap<>();
        totals.forEach((k, v) -> scaled.put(k, scale(v)));
        return scaled;
    }

    /** Signed Σ of cash movements: PAID_IN positive, PAID_OUT negative. */
    private BigDecimal cashMovementTotal(UUID sessionId) {
        return cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(sessionId).stream()
                .map(m -> m.getMovementType() == CashMovementType.PAID_IN
                        ? m.getAmount()
                        : m.getAmount().negate())
                .reduce(ZERO, BigDecimal::add);
    }

    private static BigDecimal signedAmount(OrderPaymentRecord record) {
        return record.getRecordType() == OrderPaymentRecord.RecordType.SETTLED
                ? record.getAmount()
                : record.getAmount().negate();
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private RegisterSessionSummary toSummary(RegisterSession s) {
        return new RegisterSessionSummary(
                s.getSessionId(),
                s.getTerminalId(),
                s.getLocationId(),
                s.getOpenedByClerkId(),
                s.getStatus().name(),
                s.getOpeningFloat(),
                s.getCountedCash(),
                s.getTheoreticalCash(),
                s.getOverShort(),
                s.isVarianceApproved(),
                s.getClosedByClerkId(),
                s.getOpenedAt(),
                s.getClosingStartedAt(),
                s.getClosedAt());
    }

    private static CashMovementSummary toMovementSummary(CashMovement m) {
        return new CashMovementSummary(
                m.getMovementId(),
                m.getSessionId(),
                m.getMovementType().name(),
                m.getAmount(),
                m.getReason(),
                m.getClerkId(),
                m.getOccurredAt());
    }
}
