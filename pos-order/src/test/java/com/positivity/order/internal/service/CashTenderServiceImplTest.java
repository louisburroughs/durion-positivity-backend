package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.client.CashPaymentRef;
import com.positivity.order.internal.client.InvoicingPort;
import com.positivity.order.internal.client.RecordCashPaymentCommand;
import com.positivity.order.internal.config.CashIncrements;
import com.positivity.order.internal.dto.CashTenderRequest;
import com.positivity.order.internal.dto.CashTenderResponse;
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
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ADR-0067 A8 (#2662, Order ruling §1, §5, §7 and the ACs (a)-(g)). */
@ExtendWith(MockitoExtension.class)
@DisplayName("register cash tender (ADR-0067 A8, #2662)")
class CashTenderServiceImplTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID INTENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    private static final String KEY = "tender-key-1";

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private RegisterSessionRepository registerSessionRepository;

    @Mock
    private InvoicingPort invoicingPort;

    private CashTenderServiceImpl service;
    private SalesOrder order;
    private RegisterSession session;

    @BeforeEach
    void setUp() {
        service = new CashTenderServiceImpl(
                salesOrderRepository,
                registerSessionRepository,
                invoicingPort,
                new CashIncrements(Map.of("CAD", new BigDecimal("0.05"))));
        order = SalesOrder.builder()
                .orderId(ORDER_ID)
                .status(SalesOrderStatus.PENDING_PAYMENT)
                .grandTotal(new BigDecimal("9.9800"))
                .amountPaid(new BigDecimal("0.0000"))
                .invoiceId(INVOICE_ID)
                .sessionId(SESSION_ID)
                .build();
        session = new RegisterSession();
        session.setSessionId(SESSION_ID);
        session.setTerminalId("REG-01");
        session.setStatus(RegisterSessionStatus.OPEN);
        session.setCurrencyCode("CAD");
    }

    private void ready() {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(registerSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    }

    private static CashTenderRequest tender(String tendered, String applied, String currency) {
        CashTenderRequest request = new CashTenderRequest();
        request.setCashTendered(new BigDecimal(tendered));
        request.setCashApplied(applied == null ? null : new BigDecimal(applied));
        request.setCurrencyCode(currency);
        return request;
    }

    @Test
    @DisplayName("(a) CAD due 9.98, tendered 20.00: cash 10.00, rounding +0.02, change 10.00, invoice settled for 9.98")
    void fullSettlementRoundsUp() {
        ready();
        when(invoicingPort.recordCashPayment(eq(INVOICE_ID), any())).thenReturn(new CashPaymentRef(INTENT_ID));

        CashTenderResponse response = service.recordCashTender(ORDER_ID, KEY, tender("20.00", null, "CAD"));

        assertThat(response.amountDue()).isEqualByComparingTo("9.98");
        assertThat(response.cashDue()).isEqualByComparingTo("10.00");
        assertThat(response.cashRoundingAmount()).isEqualByComparingTo("0.02");
        assertThat(response.cashIncrement()).isEqualByComparingTo("0.05");
        assertThat(response.changeDue()).isEqualByComparingTo("10.00");
        assertThat(response.balanceDue()).isZero();
        assertThat(response.paymentIntentId()).isEqualTo(INTENT_ID);
        ArgumentCaptor<RecordCashPaymentCommand> command = ArgumentCaptor.forClass(RecordCashPaymentCommand.class);
        verify(invoicingPort).recordCashPayment(eq(INVOICE_ID), command.capture());
        assertThat(command.getValue().idempotencyKey()).isEqualTo(KEY);
        assertThat(command.getValue().amount()).isEqualByComparingTo("9.98");
        assertThat(command.getValue().settledAmount()).isEqualByComparingTo("10.00");
        assertThat(command.getValue().cashRoundingAmount()).isEqualByComparingTo("0.02");
        assertThat(command.getValue().cashIncrement()).isEqualByComparingTo("0.05");
        assertThat(command.getValue().currencyCode()).isEqualTo("CAD");
        assertThat(command.getValue().sessionId()).isEqualTo(SESSION_ID);
        assertThat(command.getValue().terminalId()).isEqualTo("REG-01");
    }

    @Test
    @DisplayName("(b) due 10.02 rounds down to 10.00 with rounding -0.02")
    void fullSettlementRoundsDown() {
        order.setGrandTotal(new BigDecimal("10.0200"));
        ready();
        when(invoicingPort.recordCashPayment(any(), any())).thenReturn(new CashPaymentRef(INTENT_ID));

        CashTenderResponse response = service.recordCashTender(ORDER_ID, KEY, tender("10.00", null, "CAD"));

        assertThat(response.cashDue()).isEqualByComparingTo("10.00");
        assertThat(response.cashRoundingAmount()).isEqualByComparingTo("-0.02");
        assertThat(response.changeDue()).isZero();
    }

    @Test
    @DisplayName("(d) a currency without an increment does not round: cashIncrement null, rounding 0")
    void usdDoesNotRound() {
        session.setCurrencyCode("USD");
        order.setGrandTotal(new BigDecimal("9.9800"));
        ready();
        when(invoicingPort.recordCashPayment(any(), any())).thenReturn(new CashPaymentRef(INTENT_ID));

        CashTenderResponse response = service.recordCashTender(ORDER_ID, KEY, tender("10.00", null, "USD"));

        assertThat(response.cashDue()).isEqualByComparingTo("9.98");
        assertThat(response.cashRoundingAmount()).isZero();
        assertThat(response.cashIncrement()).isNull();
        assertThat(response.changeDue()).isEqualByComparingTo("0.02");
    }

    @Test
    @DisplayName("(e) the remainder after a card capture rounds: card 15.00 on 24.98, then cash 10.00 / applied 9.98")
    void mixedTenderRoundsOnlyTheCashRemainder() {
        order.setGrandTotal(new BigDecimal("24.9800"));
        order.setAmountPaid(new BigDecimal("15.0000"));
        ready();
        when(invoicingPort.recordCashPayment(any(), any())).thenReturn(new CashPaymentRef(INTENT_ID));

        CashTenderResponse response = service.recordCashTender(ORDER_ID, KEY, tender("10.00", null, "CAD"));

        assertThat(response.amountDue()).isEqualByComparingTo("9.98");
        assertThat(response.cashDue()).isEqualByComparingTo("10.00");
        assertThat(response.cashRoundingAmount()).isEqualByComparingTo("0.02");
    }

    @Test
    @DisplayName("(e) a partial cashApplied rounds nothing and leaves the balance to the card")
    void partialCashAppliedRoundsNothing() {
        order.setGrandTotal(new BigDecimal("24.9800"));
        ready();
        when(invoicingPort.recordCashPayment(any(), any())).thenReturn(new CashPaymentRef(INTENT_ID));

        CashTenderResponse response = service.recordCashTender(ORDER_ID, KEY, tender("10.00", "10.00", "CAD"));

        assertThat(response.amountDue()).isEqualByComparingTo("10.00");
        assertThat(response.cashDue()).isEqualByComparingTo("10.00");
        assertThat(response.cashRoundingAmount()).isZero();
        assertThat(response.cashIncrement()).isEqualByComparingTo("0.05");
        assertThat(response.balanceDue()).isEqualByComparingTo("14.98");
        assertThat(response.changeDue()).isZero();
    }

    @Test
    @DisplayName("(e) a partial cashApplied off the increment grid is CASH_AMOUNT_NOT_IN_INCREMENT")
    void partialOffTheGridIsRefused() {
        order.setGrandTotal(new BigDecimal("24.9800"));
        ready();

        assertThatThrownBy(() -> service.recordCashTender(ORDER_ID, KEY, tender("10.02", "10.02", "CAD")))
                .isInstanceOf(CashTenderRefusedException.class)
                .extracting(e -> ((CashTenderRefusedException) e).code())
                .isEqualTo(CashTenderRefusedException.Code.CASH_AMOUNT_NOT_IN_INCREMENT);
        verifyNoInteractions(invoicingPort);
    }

    @Test
    @DisplayName("(f) tendered 9.95 on cashDue 10.00 is CASH_TENDER_INSUFFICIENT and nothing is recorded")
    void insufficientTenderIsRefused() {
        ready();

        assertThatThrownBy(() -> service.recordCashTender(ORDER_ID, KEY, tender("9.95", null, "CAD")))
                .isInstanceOf(CashTenderRefusedException.class)
                .satisfies(e -> {
                    CashTenderRefusedException refused = (CashTenderRefusedException) e;
                    assertThat(refused.code()).isEqualTo(CashTenderRefusedException.Code.CASH_TENDER_INSUFFICIENT);
                    assertThat(refused.fieldErrors()).singleElement().satisfies(fe -> {
                        assertThat(fe.field()).isEqualTo("cashTendered");
                        assertThat(fe.message()).contains("10.00");
                    });
                });
        verifyNoInteractions(invoicingPort);
    }

    @Test
    @DisplayName("an order without a register session is CASH_TENDER_SESSION_REQUIRED")
    void noSessionIsRefused() {
        order.setSessionId(null);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.recordCashTender(ORDER_ID, KEY, tender("20.00", null, "CAD")))
                .isInstanceOf(CashTenderRefusedException.class)
                .extracting(e -> ((CashTenderRefusedException) e).code())
                .isEqualTo(CashTenderRefusedException.Code.CASH_TENDER_SESSION_REQUIRED);
        verifyNoInteractions(invoicingPort, registerSessionRepository);
    }

    @Test
    @DisplayName("a closed drawer is REGISTER_SESSION_CONFLICT")
    void closedSessionIsAConflict() {
        session.setStatus(RegisterSessionStatus.CLOSED);
        ready();

        assertThatThrownBy(() -> service.recordCashTender(ORDER_ID, KEY, tender("20.00", null, "CAD")))
                .isInstanceOf(RegisterSessionConflictException.class);
        verifyNoInteractions(invoicingPort);
    }

    @Test
    @DisplayName("an order that is not PENDING_PAYMENT is ORDER_INVALID_STATE_TRANSITION")
    void wrongStateIsAConflict() {
        order.setStatus(SalesOrderStatus.COMPLETED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.recordCashTender(ORDER_ID, KEY, tender("20.00", null, "CAD")))
                .isInstanceOf(InvalidOrderStateTransitionException.class);
        verifyNoInteractions(invoicingPort);
    }

    @Test
    @DisplayName("cash in another currency than the drawer's is CURRENCY_NOT_SUPPORTED")
    void foreignCashIsRefused() {
        ready();

        assertThatThrownBy(() -> service.recordCashTender(ORDER_ID, KEY, tender("20.00", null, "USD")))
                .isInstanceOf(CurrencyNotSupportedException.class);
        verifyNoInteractions(invoicingPort);
    }

    @Test
    @DisplayName("a tender with more decimals than the currency is AMOUNT_PRECISION_EXCEEDS_CURRENCY, never rounded")
    void subCentTenderIsRefused() {
        ready();

        assertThatThrownBy(() -> service.recordCashTender(ORDER_ID, KEY, tender("20.005", null, "CAD")))
                .isInstanceOf(CashMovementTaxRefusedException.class);
        verifyNoInteractions(invoicingPort);
    }

    @Test
    @DisplayName("cashApplied above the balance is a request error")
    void appliedAboveTheBalanceIsInvalid() {
        ready();

        assertThatThrownBy(() -> service.recordCashTender(ORDER_ID, KEY, tender("20.00", "15.00", "CAD")))
                .isInstanceOf(SalesOrderRequestValidationException.class);
        verifyNoInteractions(invoicingPort);
    }

    @Test
    @DisplayName("a settled order has nothing due: ORDER_CONFLICT, refresh first")
    void nothingDueIsAConflict() {
        order.setAmountPaid(new BigDecimal("9.9800"));
        ready();

        assertThatThrownBy(() -> service.recordCashTender(ORDER_ID, KEY, tender("20.00", null, "CAD")))
                .isInstanceOf(OrderConflictException.class);
        verifyNoInteractions(invoicingPort);
    }

    @Test
    void unknownOrderIsNotFound() {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordCashTender(ORDER_ID, KEY, tender("20.00", null, "CAD")))
                .isInstanceOf(SalesOrderNotFoundException.class);
    }

    @Test
    void blankKeyIsInvalid() {
        assertThatThrownBy(() -> service.recordCashTender(ORDER_ID, "  ", tender("20.00", null, "CAD")))
                .isInstanceOf(SalesOrderRequestValidationException.class);
        verifyNoInteractions(salesOrderRepository);
    }
}
