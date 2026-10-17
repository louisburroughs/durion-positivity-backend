package com.positivity.order.internal.service;

import com.positivity.order.internal.client.CashPaymentRef;
import com.positivity.order.internal.client.InvoicingPort;
import com.positivity.order.internal.client.RecordCashPaymentCommand;
import com.positivity.order.internal.config.CashIncrements;
import com.positivity.order.internal.dto.CashTenderRequest;
import com.positivity.order.internal.dto.CashTenderResponse;
import com.positivity.order.internal.entity.OrderPaymentRecord;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.CashMovementTaxRefusedException;
import com.positivity.order.internal.exception.CashTenderRefusedException;
import com.positivity.order.internal.exception.CurrencyNotSupportedException;
import com.positivity.order.internal.exception.InvalidOrderStateTransitionException;
import com.positivity.order.internal.exception.OrderConflictException;
import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.exception.SalesOrderRequestValidationException;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The register's cash arithmetic (ADR-0067 A8, PC-7 (b); #2662, Order ruling §1 and §5).
 *
 * <p>A full cash settlement rounds: {@code amountDue = grandTotal − amountPaid} at the currency's exponent, {@code
 * cashDue = round(amountDue)} HALF_UP to the cash increment, {@code cashRoundingAmount = cashDue − amountDue}. The
 * customer pays {@code cashDue}; the invoice is settled for {@code amountDue}; the difference is accounting's 6050
 * line. A partial cash amount ({@code cashApplied} below the balance) rounds nothing: it is the cashier's statement
 * of coins taken, so it must already be a whole multiple of the increment. Only cash rounds; a currency without an
 * increment is a no-op.
 *
 * <p>pos-order writes nothing here: pos-invoice records the tender (idempotent on the key) and publishes the settled
 * fact, from which {@link PaymentEventsListener} records the payment and completes the order. pos-invoice is the
 * authority on the open balance; when it disagrees with the remainder projected here the tender is 409 {@code
 * ORDER_CONFLICT} and the register retries with fresh figures.
 */
@Service
@Slf4j
public class CashTenderServiceImpl implements CashTenderService {

    private final SalesOrderRepository salesOrderRepository;
    private final RegisterSessionRepository registerSessionRepository;
    private final OrderPaymentRecordRepository paymentRecordRepository;
    private final InvoicingPort invoicingPort;
    private final CashIncrements cashIncrements;

    public CashTenderServiceImpl(
            @NonNull SalesOrderRepository salesOrderRepository,
            @NonNull RegisterSessionRepository registerSessionRepository,
            @NonNull OrderPaymentRecordRepository paymentRecordRepository,
            @NonNull InvoicingPort invoicingPort,
            @NonNull CashIncrements cashIncrements) {
        this.salesOrderRepository = salesOrderRepository;
        this.registerSessionRepository = registerSessionRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.invoicingPort = invoicingPort;
        this.cashIncrements = cashIncrements;
    }

    // Deliberately not transactional: reads, then a synchronous call to pos-invoice; a transaction here would pin
    // a pooled connection for the whole round-trip (the #2342/#2344 pattern).
    @Override
    public @NonNull CashTenderResponse recordCashTender(
            @NonNull UUID orderId, @NonNull String idempotencyKey, @NonNull CashTenderRequest request) {
        String key = idempotencyKey.trim();
        if (key.isEmpty()) {
            throw new SalesOrderRequestValidationException("Idempotency-Key is required for a cash tender");
        }
        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        // Replay after the settled fact has landed: the fact's gatewayReference is the register's key, so the
        // order's own CASH record answers with the recorded figures before any state or balance gate (the register
        // may have lost the first response). Before the fact lands, the recomputation below yields the same figures
        // and pos-invoice replays the stored intent under the same key.
        Optional<OrderPaymentRecord> recorded = paymentRecordRepository.findByOrderId(orderId).stream()
                .filter(r -> r.getRecordType() == OrderPaymentRecord.RecordType.SETTLED)
                .filter(r -> "CASH".equalsIgnoreCase(r.getMethodType()))
                .filter(r -> key.equals(r.getReference()))
                .findFirst();
        if (recorded.isPresent()) {
            return replay(order, recorded.get(), request);
        }
        if (order.getStatus() != SalesOrderStatus.PENDING_PAYMENT) {
            throw new InvalidOrderStateTransitionException(orderId, order.getStatus(), SalesOrderStatus.COMPLETED);
        }
        if (order.getInvoiceId() == null) {
            throw new OrderConflictException("Order " + orderId + " has no invoice to apply a cash tender to");
        }
        RegisterSession session = requireOpenDrawer(order);
        String currency = session.getCurrencyCode();
        String requested = request.getCurrencyCode() == null
                ? ""
                : request.getCurrencyCode().trim().toUpperCase(Locale.ROOT);
        if (!currency.equals(requested)) {
            throw new CurrencyNotSupportedException("currencyCode " + request.getCurrencyCode()
                    + " is not the drawer's currency " + currency + "; cash is tendered in the drawer's currency");
        }
        int exponent = DrawerAmounts.exponent(currency);
        requireRepresentable(request.getCashTendered(), "cashTendered", currency);
        if (request.getCashApplied() != null) {
            requireRepresentable(request.getCashApplied(), "cashApplied", currency);
        }

        BigDecimal cashTendered = request.getCashTendered().setScale(exponent, RoundingMode.UNNECESSARY);
        BigDecimal amountDue = amountDue(order, exponent);
        if (amountDue.signum() <= 0) {
            throw new OrderConflictException(
                    "Order " + orderId + " has no balance due; refresh the order before tendering cash");
        }
        BigDecimal increment = cashIncrements.of(currency).orElse(null);
        BigDecimal cashApplied = request.getCashApplied() == null
                ? null
                : request.getCashApplied().setScale(exponent, RoundingMode.UNNECESSARY);
        if (cashApplied != null && cashApplied.compareTo(amountDue) > 0) {
            throw new SalesOrderRequestValidationException(
                    "cashApplied " + cashApplied + " exceeds the balance due " + amountDue);
        }

        CashRounding.Rounded rounded;
        if (cashApplied != null && cashApplied.compareTo(amountDue) < 0) {
            // A partial cash amount is the cashier's statement of coins taken: it rounds nothing and must
            // already be a whole number of increments (Order ruling §1, decision 7).
            if (!CashRounding.isMultipleOf(cashApplied, increment)) {
                throw new CashTenderRefusedException(
                        CashTenderRefusedException.Code.CASH_AMOUNT_NOT_IN_INCREMENT,
                        "cashApplied " + cashApplied + " is not a whole multiple of the " + currency
                                + " cash increment " + increment,
                        List.of(new ApiError.FieldError("cashApplied", "must be a multiple of " + increment)));
            }
            rounded = new CashRounding.Rounded(
                    cashApplied, cashApplied, BigDecimal.ZERO.setScale(exponent, RoundingMode.UNNECESSARY), increment);
        } else {
            rounded = CashRounding.round(amountDue, increment, exponent);
        }
        if (cashTendered.compareTo(rounded.cashDue()) < 0) {
            throw new CashTenderRefusedException(
                    CashTenderRefusedException.Code.CASH_TENDER_INSUFFICIENT,
                    "cashTendered " + cashTendered + " is below the cash due " + rounded.cashDue()
                            + "; state cashApplied for a split tender",
                    List.of(new ApiError.FieldError("cashTendered", "cashDue " + rounded.cashDue())));
        }

        CashPaymentRef ref = invoicingPort.recordCashPayment(
                order.getInvoiceId(),
                new RecordCashPaymentCommand(
                        key,
                        rounded.amountDue(),
                        rounded.cashDue(),
                        rounded.cashRoundingAmount(),
                        rounded.cashIncrement(),
                        currency,
                        session.getSessionId(),
                        session.getTerminalId(),
                        SecurityContextHelper.getCurrentUsernameOrDefault(null)));
        BigDecimal balanceDue = amountDue.subtract(rounded.amountDue()).max(BigDecimal.ZERO);
        log.info(
                "Cash tender recorded: order={} intent={} amountDue={} cashDue={} rounding={} increment={} change={} {}",
                orderId,
                ref.paymentIntentId(),
                rounded.amountDue(),
                rounded.cashDue(),
                rounded.cashRoundingAmount(),
                rounded.cashIncrement(),
                cashTendered.subtract(rounded.cashDue()),
                currency);
        return new CashTenderResponse(
                orderId,
                order.getInvoiceId(),
                ref.paymentIntentId(),
                rounded.amountDue(),
                rounded.cashIncrement(),
                rounded.cashDue(),
                rounded.cashRoundingAmount(),
                cashTendered,
                cashTendered.subtract(rounded.cashDue()),
                currency,
                balanceDue);
    }

    private CashTenderResponse replay(SalesOrder order, OrderPaymentRecord record, CashTenderRequest request) {
        BigDecimal applied = record.getAmount();
        BigDecimal cash = record.cashAmount();
        BigDecimal rounding = record.getCashRoundingAmount() == null ? BigDecimal.ZERO : record.getCashRoundingAmount();
        BigDecimal tendered = request.getCashTendered() == null ? cash : request.getCashTendered();
        BigDecimal balance = order.getBalanceDue() == null ? BigDecimal.ZERO : order.getBalanceDue();
        log.info(
                "Cash tender replayed from its record: order={} intent={} key={}",
                order.getOrderId(),
                record.getPaymentIntentId(),
                record.getReference());
        return new CashTenderResponse(
                order.getOrderId(),
                order.getInvoiceId(),
                record.getPaymentIntentId(),
                applied,
                record.getCashIncrement(),
                cash,
                rounding,
                tendered,
                tendered.subtract(cash).max(BigDecimal.ZERO),
                record.getCurrencyCode() == null ? "" : record.getCurrencyCode(),
                balance);
    }

    private RegisterSession requireOpenDrawer(SalesOrder order) {
        UUID sessionId = order.getSessionId();
        if (sessionId == null) {
            throw new CashTenderRefusedException(
                    CashTenderRefusedException.Code.CASH_TENDER_SESSION_REQUIRED,
                    "Order " + order.getOrderId() + " has no register session; cash needs a drawer to go into",
                    List.of(new ApiError.FieldError("sessionId", "the order must be on an open register session")));
        }
        RegisterSession session = registerSessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new RegisterSessionConflictException(
                        "Register session " + sessionId + " of order " + order.getOrderId() + " does not exist"));
        if (session.getStatus() != RegisterSessionStatus.OPEN) {
            throw new RegisterSessionConflictException("Register session " + sessionId + " is " + session.getStatus()
                    + "; a cash tender, movement or close needs an OPEN session");
        }
        return session;
    }

    /** The remainder at the drawer's exponent: the cents the register shows, never a sub-cent residue. */
    private static BigDecimal amountDue(SalesOrder order, int exponent) {
        BigDecimal total = order.getGrandTotal().setScale(exponent, RoundingMode.HALF_UP);
        BigDecimal paid = (order.getAmountPaid() == null ? BigDecimal.ZERO : order.getAmountPaid())
                .setScale(exponent, RoundingMode.HALF_UP);
        return total.subtract(paid);
    }

    private static void requireRepresentable(@Nullable BigDecimal amount, String field, String currency) {
        if (amount != null && !DrawerAmounts.representable(amount, currency)) {
            throw new CashMovementTaxRefusedException(
                    CashMovementTaxRefusedException.Code.AMOUNT_PRECISION_EXCEEDS_CURRENCY,
                    field + " " + amount + " has more decimals than " + currency + " allows; nothing is rounded",
                    List.of(new ApiError.FieldError(
                            field, "at most " + DrawerAmounts.exponent(currency) + " decimals")));
        }
    }
}
