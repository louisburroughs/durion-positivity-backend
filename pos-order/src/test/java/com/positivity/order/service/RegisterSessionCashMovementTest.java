package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.order.internal.config.FunctionalCurrency;
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.entity.CashMovement;
import com.positivity.order.internal.entity.CashMovementApproval;
import com.positivity.order.internal.entity.CashMovementReason;
import com.positivity.order.internal.entity.ExtAccountingPettyExpenseCategory;
import com.positivity.order.internal.entity.ExtAccountingRegisterFloat;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.CashMovementIdempotencyConflictException;
import com.positivity.order.internal.exception.CashMovementRefusedException;
import com.positivity.order.internal.exception.CashMovementRefusedException.Refusal;
import com.positivity.order.internal.exception.CurrencyNotSupportedException;
import com.positivity.order.internal.exception.RegisterSessionRequestValidationException;
import com.positivity.order.internal.repository.CashMovementRepository;
import com.positivity.order.internal.repository.ExtAccountingPettyExpenseCategoryRepository;
import com.positivity.order.internal.repository.ExtAccountingRegisterFloatRepository;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.service.model.CashMovementCommand;
import com.positivity.order.internal.service.model.CashMovementOptions;
import com.positivity.order.internal.service.model.CashMovementResult;
import com.positivity.order.internal.service.model.SessionPolicyView;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.shared.id.UUIDv7Generator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CAP:550 S16 (#2512; SPEC-accounting-workspace §4.6, AW15, AW16, AW19, AW31): fixed reasons,
 * running-total limits, manager approval, the float match, idempotent replay, the cashier from the
 * security context and the close fact v2.
 */
@DisplayName("RegisterSessionServiceImpl — drawer movements (CAP:550 S16)")
class RegisterSessionCashMovementTest {

    private static final String TERMINAL = "T-1";
    private static final UUID SESSION_ID = UUID.fromString("01900000-0000-7000-8000-00000000a001");
    private static final UUID MANAGER_ID = UUID.fromString("01900000-0000-7000-8000-00000000b001");
    private static final UUID APPROVAL_ID = UUID.fromString("01900000-0000-7000-8000-00000000c001");
    private static final UUID LOCATION = UUID.fromString("01900000-0000-7000-8000-00000000d001");
    private static final UUID OTHER_LOCATION = UUID.fromString("01900000-0000-7000-8000-00000000d002");
    private static final UUID CASHIER_ID = UUID.fromString("01900000-0000-7000-8000-00000000e001");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);

    private final RegisterSessionRepository sessions = mock(RegisterSessionRepository.class);
    private final CashMovementRepository movements = mock(CashMovementRepository.class);
    private final SalesOrderRepository salesOrders = mock(SalesOrderRepository.class);
    private final OrderPaymentRecordRepository payments = mock(OrderPaymentRecordRepository.class);
    private final OrderDomainEventPublisher publisher = mock(OrderDomainEventPublisher.class);
    private final SessionPolicyService policyService = mock(SessionPolicyService.class);
    private final CashMovementApprovalService approvalService = mock(CashMovementApprovalService.class);
    private final ExtAccountingRegisterFloatRepository floats = mock(ExtAccountingRegisterFloatRepository.class);
    private final ExtAccountingPettyExpenseCategoryRepository categories =
            mock(ExtAccountingPettyExpenseCategoryRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    /** The session's movements as the repository holds them. */
    private final List<CashMovement> recorded = new ArrayList<>();

    private RegisterSession session;
    private RegisterSessionServiceImpl service;

    private static SessionPolicyView policy(boolean pettyAllowed, String pettyLimit) {
        return new SessionPolicyView(
                1L, pettyAllowed, new BigDecimal(pettyLimit), false, null, new BigDecimal("5.0000"), "USD");
    }

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
        when(meters.getIfAvailable()).thenReturn(meterRegistry);
        service = new RegisterSessionServiceImpl(
                sessions,
                movements,
                salesOrders,
                payments,
                publisher,
                new HouseAccountReplica(mock(ExtCustomerRepository.class)),
                policyService,
                approvalService,
                floats,
                categories,
                new FunctionalCurrency("USD"),
                CLOCK,
                meters);
        session = RegisterSession.builder()
                .sessionId(SESSION_ID)
                .version(3L)
                .terminalId(TERMINAL)
                .locationId(LOCATION)
                .openedByClerkId("opener")
                .status(RegisterSessionStatus.OPEN)
                .currencyCode("USD")
                .openingFloat(new BigDecimal("200.0000"))
                .openedAt(Instant.parse("2026-10-07T08:00:00Z"))
                .build();
        lenient().when(sessions.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(session));
        lenient().when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
        lenient().when(policyService.current()).thenReturn(policy(true, "50.0000"));
        lenient()
                .when(movements.findBySessionIdOrderByOccurredAtAsc(SESSION_ID))
                .thenAnswer(_ -> List.copyOf(recorded));
        lenient()
                .when(movements.findByRequestId(any()))
                .thenAnswer(inv -> recorded.stream()
                        .filter(m -> inv.getArgument(0).equals(m.getRequestId()))
                        .findFirst());
        lenient().when(movements.saveAndFlush(any())).thenAnswer(inv -> {
            CashMovement m = inv.getArgument(0);
            m.setMovementId(UUIDv7Generator.generate());
            recorded.add(m);
            return m;
        });
        lenient()
                .when(categories.findByCode("SHOP_SUPPLIES"))
                .thenReturn(Optional.of(category("SHOP_SUPPLIES", "ACTIVE")));
        signIn("cashier");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String username) {
        signIn(username, LocationScope.unscoped());
    }

    private static void signIn(String username, LocationScope scope) {
        var token = new UsernamePasswordAuthenticationToken(username, "n/a", List.of());
        token.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                username,
                GatewaySecurityConstants.DETAIL_USER_ID,
                CASHIER_ID,
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                scope));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static ExtAccountingPettyExpenseCategory category(String code, String status) {
        return ExtAccountingPettyExpenseCategory.builder()
                .pettyExpenseCategoryId(UUIDv7Generator.generate())
                .code(code)
                .label("Shop supplies")
                .status(status)
                .aggregateVersion(1L)
                .syncedAt(Instant.now(CLOCK))
                .build();
    }

    private static CashMovementCommand petty(UUID requestId, String amount, String token) {
        return new CashMovementCommand(
                SESSION_ID,
                requestId,
                "PETTY_EXPENSE",
                new BigDecimal(amount),
                "USD",
                "SHOP_SUPPLIES",
                null,
                null,
                "R-" + amount,
                "gloves",
                token);
    }

    private static CashMovementCommand bankDrop(UUID requestId, String amount, String bag) {
        return new CashMovementCommand(
                SESSION_ID, requestId, "BANK_DROP", new BigDecimal(amount), "USD", null, null, bag, null, null, null);
    }

    private static CashMovementCommand floatChange(String reason, String amount, String token) {
        return new CashMovementCommand(
                SESSION_ID,
                UUIDv7Generator.generate(),
                reason,
                new BigDecimal(amount),
                "USD",
                null,
                null,
                null,
                null,
                null,
                token);
    }

    private void managerApproves(CashMovementReason reason, String amount, String category) {
        when(approvalService.use(
                        eq("token-1"),
                        eq(SESSION_ID),
                        eq(reason),
                        any(),
                        eq("USD"),
                        category == null ? any() : eq(category),
                        any()))
                .thenReturn(CashMovementApproval.builder()
                        .approvalId(APPROVAL_ID)
                        .approverUserId(MANAGER_ID)
                        .amount(new BigDecimal(amount))
                        .build());
    }

    private static CashMovementRefusedException refusal(Throwable thrown) {
        return (CashMovementRefusedException) thrown;
    }

    @Nested
    @DisplayName("limits on the running total (AC1, AC13, mutation guard [M])")
    class Limits {

        @Test
        @DisplayName(
                "AC1: 30.00 then 25.00 against a 50.00 limit — the second needs a manager; with a token, approvedBy")
        void runningTotalNeedsManagerAboveLimit() {
            CashMovementResult first = service.recordCashMovement(petty(UUIDv7Generator.generate(), "30.00", null));
            assertThat(first.replayed()).isFalse();
            assertThat(first.movement().approvedBy()).isNull();

            // [M] the single movement (25.00) is below the limit; only the running total (55.00) is above.
            UUID second = UUIDv7Generator.generate();
            assertThatThrownBy(() -> service.recordCashMovement(petty(second, "25.00", null)))
                    .isInstanceOf(CashMovementRefusedException.class)
                    .satisfies(e -> assertThat(refusal(e).refusal()).isEqualTo(Refusal.APPROVAL_REQUIRED));
            assertThat(recorded).hasSize(1);
            assertThat(meterRegistry
                            .get(RegisterSessionServiceImpl.REFUSED_COUNTER)
                            .tag("code", "CASH_MOVEMENT_APPROVAL_REQUIRED")
                            .counter()
                            .count())
                    .isEqualTo(1.0);

            managerApproves(CashMovementReason.PETTY_EXPENSE, "25.00", "SHOP_SUPPLIES");
            CashMovementResult approved = service.recordCashMovement(petty(second, "25.00", "token-1"));

            assertThat(approved.movement().approvedBy()).isEqualTo(MANAGER_ID);
            assertThat(recorded.get(1).getApprovalId()).isEqualTo(APPROVAL_ID);
            assertThat(recorded.get(1).getMovementType().name()).isEqualTo("PAID_OUT");
        }

        @Test
        @DisplayName(
                "AC13: a retry with the same requestId returns the first result and records nothing; options show 55.00")
        void replayReturnsFirstResult() {
            service.recordCashMovement(petty(UUIDv7Generator.generate(), "30.00", null));
            UUID approvedRequest = UUIDv7Generator.generate();
            managerApproves(CashMovementReason.PETTY_EXPENSE, "25.00", "SHOP_SUPPLIES");
            CashMovementResult first = service.recordCashMovement(petty(approvedRequest, "25.00", "token-1"));

            // The token is used by now; the replay must not need it again.
            CashMovementResult replay = service.recordCashMovement(petty(approvedRequest, "25.00", "token-1"));

            assertThat(replay.replayed()).isTrue();
            assertThat(replay.movement()).isEqualTo(first.movement());
            assertThat(recorded).hasSize(2);
            verify(approvalService).use(anyString(), any(), any(), any(), any(), any(), any());

            CashMovementOptions options = service.cashMovementOptions(SESSION_ID);
            assertThat(options.reasons())
                    .filteredOn(r -> r.reason().equals("PETTY_EXPENSE"))
                    .singleElement()
                    .satisfies(r -> {
                        assertThat(r.runningTotal()).isEqualByComparingTo("55.00");
                        assertThat(r.cashierLimit()).isEqualByComparingTo("50.00");
                        assertThat(r.allowedNow()).isTrue();
                        assertThat(r.requiredFields()).containsExactly("categoryCode", "receiptReference", "note");
                    });
        }

        @Test
        @DisplayName("§8.2: the same requestId with another payload is IDEMPOTENCY_CONFLICT")
        void sameRequestIdOtherPayloadConflicts() {
            UUID requestId = UUIDv7Generator.generate();
            service.recordCashMovement(petty(requestId, "10.00", null));

            assertThatThrownBy(() -> service.recordCashMovement(petty(requestId, "11.00", null)))
                    .isInstanceOf(CashMovementIdempotencyConflictException.class);
            assertThat(recorded).hasSize(1);
        }

        @Test
        @DisplayName("a bank drop has no limit and needs no manager, whatever the amount")
        void bankDropUnlimited() {
            CashMovementResult drop =
                    service.recordCashMovement(bankDrop(UUIDv7Generator.generate(), "5000.00", "BAG-1"));

            assertThat(drop.movement().bagNumber()).isEqualTo("BAG-1");
            verify(approvalService, never()).use(anyString(), any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("policy switches, categories and fields (AC3, AC4, AC9)")
    class Rules {

        @Test
        @DisplayName(
                "AC3: petty switched off mid-session refuses the next one; the earlier ones stay on the close fact")
        void switchedOffIsNotRetroactive() {
            service.recordCashMovement(petty(UUIDv7Generator.generate(), "10.00", null));
            service.recordCashMovement(petty(UUIDv7Generator.generate(), "12.00", null));
            when(policyService.current()).thenReturn(policy(false, "50.0000"));

            assertThatThrownBy(() -> service.recordCashMovement(petty(UUIDv7Generator.generate(), "5.00", null)))
                    .satisfies(e -> assertThat(refusal(e).refusal()).isEqualTo(Refusal.TYPE_NOT_ALLOWED));

            closeSession();
            ArgumentCaptor<RegisterSessionClosedV1> fact = ArgumentCaptor.forClass(RegisterSessionClosedV1.class);
            verify(publisher).publishRegisterSessionClosed(any(), fact.capture());
            assertThat(fact.getValue().movements())
                    .extracting(RegisterSessionClosedV1.Movement::reason)
                    .containsExactly("PETTY_EXPENSE", "PETTY_EXPENSE");
        }

        @Test
        @DisplayName("vendor cash on delivery is off by default (until S24): CASH_MOVEMENT_TYPE_NOT_ALLOWED")
        void vendorCodOffByDefault() {
            CashMovementCommand cod = new CashMovementCommand(
                    SESSION_ID,
                    UUIDv7Generator.generate(),
                    "VENDOR_COD",
                    new BigDecimal("40.00"),
                    "USD",
                    null,
                    UUIDv7Generator.generate(),
                    null,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.recordCashMovement(cod))
                    .satisfies(e -> assertThat(refusal(e).refusal()).isEqualTo(Refusal.TYPE_NOT_ALLOWED));
        }

        @Test
        @DisplayName("a category deactivated since the picker read: PETTY_EXPENSE_CATEGORY_UNKNOWN")
        void inactiveCategoryRefused() {
            when(categories.findByCode("SHOP_SUPPLIES")).thenReturn(Optional.of(category("SHOP_SUPPLIES", "INACTIVE")));

            assertThatThrownBy(() -> service.recordCashMovement(petty(UUIDv7Generator.generate(), "10.00", null)))
                    .satisfies(e -> assertThat(refusal(e).refusal()).isEqualTo(Refusal.CATEGORY_UNKNOWN));
        }

        @Test
        @DisplayName("AC4: the movement's clerkId is the caller from the security context")
        void clerkFromSecurityContext() {
            signIn("cashier-7");

            CashMovementResult result = service.recordCashMovement(petty(UUIDv7Generator.generate(), "10.00", null));

            assertThat(result.movement().clerkId()).isEqualTo("cashier-7");
        }

        @Test
        @DisplayName("AC9: a bank drop without a bag number, or a petty expense missing a field, is a 400")
        void requiredFields() {
            assertThatThrownBy(() -> service.recordCashMovement(bankDrop(UUIDv7Generator.generate(), "100.00", " ")))
                    .isInstanceOf(RegisterSessionRequestValidationException.class)
                    .hasMessageContaining("bagNumber");
            for (CashMovementCommand missing : List.of(
                    new CashMovementCommand(
                            SESSION_ID,
                            UUIDv7Generator.generate(),
                            "PETTY_EXPENSE",
                            BigDecimal.TEN,
                            "USD",
                            null,
                            null,
                            null,
                            "R",
                            "n",
                            null),
                    new CashMovementCommand(
                            SESSION_ID,
                            UUIDv7Generator.generate(),
                            "PETTY_EXPENSE",
                            BigDecimal.TEN,
                            "USD",
                            "SHOP_SUPPLIES",
                            null,
                            null,
                            null,
                            "n",
                            null),
                    new CashMovementCommand(
                            SESSION_ID,
                            UUIDv7Generator.generate(),
                            "PETTY_EXPENSE",
                            BigDecimal.TEN,
                            "USD",
                            "SHOP_SUPPLIES",
                            null,
                            null,
                            "R",
                            null,
                            null))) {
                assertThatThrownBy(() -> service.recordCashMovement(missing))
                        .isInstanceOf(RegisterSessionRequestValidationException.class);
            }
            assertThatThrownBy(() -> service.recordCashMovement(new CashMovementCommand(
                            SESSION_ID,
                            UUIDv7Generator.generate(),
                            "OTHER",
                            BigDecimal.TEN,
                            "USD",
                            null,
                            null,
                            null,
                            null,
                            "x",
                            null)))
                    .isInstanceOf(RegisterSessionRequestValidationException.class);
            assertThatThrownBy(() -> service.recordCashMovement(new CashMovementCommand(
                            SESSION_ID, null, "BANK_DROP", BigDecimal.TEN, "USD", null, null, "B", null, null, null)))
                    .isInstanceOf(RegisterSessionRequestValidationException.class)
                    .hasMessageContaining("requestId");
            assertThat(recorded).isEmpty();
        }
    }

    @Nested
    @DisplayName("float changes (AC8, AW16)")
    class FloatChanges {

        @BeforeEach
        void configuredFloatRose() {
            when(floats.findByRegisterId(TERMINAL))
                    .thenReturn(Optional.of(ExtAccountingRegisterFloat.builder()
                            .registerId(TERMINAL)
                            .locationId(LOCATION)
                            .amount(new BigDecimal("250.0000"))
                            .currencyCode("USD")
                            .build()));
        }

        @Test
        @DisplayName("AC8: configured 250.00, drawer 200.00 — FLOAT_INCREASE 50.00 with a token is accepted")
        void matchingIncreaseAccepted() {
            managerApproves(CashMovementReason.FLOAT_INCREASE, "50.00", null);

            CashMovementResult result = service.recordCashMovement(floatChange("FLOAT_INCREASE", "50.00", "token-1"));

            assertThat(result.movement().movementType()).isEqualTo("PAID_IN");
            assertThat(result.movement().approvedBy()).isEqualTo(MANAGER_ID);
            // The drawer now holds the configured float: a second increase matches nothing.
            assertThatThrownBy(() -> service.recordCashMovement(floatChange("FLOAT_INCREASE", "50.00", "token-1")))
                    .satisfies(e -> assertThat(refusal(e).refusal()).isEqualTo(Refusal.FLOAT_CHANGE_NOT_RECORDED));
        }

        @Test
        @DisplayName("AC8: 40.00 matches no recorded change — FLOAT_CHANGE_NOT_RECORDED")
        void mismatchedAmountRefused() {
            assertThatThrownBy(() -> service.recordCashMovement(floatChange("FLOAT_INCREASE", "40.00", "token-1")))
                    .satisfies(e -> assertThat(refusal(e).refusal()).isEqualTo(Refusal.FLOAT_CHANGE_NOT_RECORDED));
        }

        @Test
        @DisplayName("the wrong direction matches nothing either")
        void wrongDirectionRefused() {
            assertThatThrownBy(() -> service.recordCashMovement(floatChange("FLOAT_DECREASE", "50.00", "token-1")))
                    .satisfies(e -> assertThat(refusal(e).refusal()).isEqualTo(Refusal.FLOAT_CHANGE_NOT_RECORDED));
        }

        @Test
        @DisplayName("#2577 (ADR-0067 PC-9): a configured float in another currency than the drawer's (a CAD copy"
                + " arriving after a USD drawer opened) is 422 CURRENCY_NOT_SUPPORTED; nothing is compared or recorded")
        void floatInAnotherCurrencyIsNeverCompared() {
            when(floats.findByRegisterId(TERMINAL))
                    .thenReturn(Optional.of(ExtAccountingRegisterFloat.builder()
                            .registerId(TERMINAL)
                            .locationId(LOCATION)
                            .amount(new BigDecimal("250.0000"))
                            .currencyCode("CAD")
                            .build()));

            assertThatThrownBy(() -> service.recordCashMovement(floatChange("FLOAT_INCREASE", "50.00", "token-1")))
                    .isInstanceOf(CurrencyNotSupportedException.class)
                    .hasMessageContaining("CAD")
                    .hasMessageContaining("USD");
            assertThat(recorded).isEmpty();
            verify(approvalService, never()).use(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("AC8: the matching increase without a token — CASH_MOVEMENT_APPROVAL_REQUIRED (always a manager)")
        void floatChangeAlwaysNeedsManager() {
            assertThatThrownBy(() -> service.recordCashMovement(floatChange("FLOAT_INCREASE", "50.00", null)))
                    .satisfies(e -> assertThat(refusal(e).refusal()).isEqualTo(Refusal.APPROVAL_REQUIRED));
        }
    }

    @Test
    @DisplayName(
            "AC10: the close fact is schema 2 with one entry per movement — reason, amount, details, clerk, approver")
    void closeFactCarriesMovements() {
        service.recordCashMovement(petty(UUIDv7Generator.generate(), "20.00", null));
        service.recordCashMovement(bankDrop(UUIDv7Generator.generate(), "300.00", "BAG-7"));
        when(floats.findByRegisterId(TERMINAL))
                .thenReturn(Optional.of(ExtAccountingRegisterFloat.builder()
                        .registerId(TERMINAL)
                        .locationId(LOCATION)
                        .amount(new BigDecimal("150.0000"))
                        .currencyCode("USD")
                        .build()));
        when(approvalService.use(
                        eq("token-1"),
                        eq(SESSION_ID),
                        eq(CashMovementReason.FLOAT_DECREASE),
                        any(),
                        eq("USD"),
                        any(),
                        any()))
                .thenReturn(CashMovementApproval.builder()
                        .approvalId(APPROVAL_ID)
                        .approverUserId(MANAGER_ID)
                        .build());
        service.recordCashMovement(floatChange("FLOAT_DECREASE", "50.00", "token-1"));

        closeSession();

        ArgumentCaptor<RegisterSessionClosedV1> fact = ArgumentCaptor.forClass(RegisterSessionClosedV1.class);
        verify(publisher).publishRegisterSessionClosed(any(), fact.capture());
        assertThat(RegisterSessionClosedV1.SCHEMA_VERSION).isEqualTo(2);
        assertThat(fact.getValue().currencyCode()).isEqualTo("USD");
        List<RegisterSessionClosedV1.Movement> facts = fact.getValue().movements();
        assertThat(facts).hasSize(3);
        assertThat(facts.get(0)).satisfies(m -> {
            assertThat(m.reason()).isEqualTo("PETTY_EXPENSE");
            assertThat(m.direction()).isEqualTo("OUT");
            assertThat(m.amount()).isEqualByComparingTo("20.00");
            assertThat(m.categoryCode()).isEqualTo("SHOP_SUPPLIES");
            assertThat(m.receiptReference()).isEqualTo("R-20.00");
            assertThat(m.clerkId()).isEqualTo("cashier");
            assertThat(m.clerkUserId()).isEqualTo(CASHIER_ID);
            assertThat(m.currencyCode()).isEqualTo("USD");
            assertThat(m.approvedBy()).isNull();
        });
        assertThat(facts.get(1)).satisfies(m -> {
            assertThat(m.reason()).isEqualTo("BANK_DROP");
            assertThat(m.bagNumber()).isEqualTo("BAG-7");
        });
        assertThat(facts.get(2)).satisfies(m -> {
            assertThat(m.reason()).isEqualTo("FLOAT_DECREASE");
            assertThat(m.approvedBy()).isEqualTo(MANAGER_ID);
        });
        // Theoretical cash still adds every movement signed by direction: 200 − 20 − 300 − 50.
        assertThat(fact.getValue().cashMovementTotal()).isEqualByComparingTo("-370.00");
        assertThat(fact.getValue().theoreticalCash()).isEqualByComparingTo("-170.00");
    }

    @Nested
    @DisplayName("review round: identifiers, currency, location scope, float location (#2569)")
    class ReviewRound {

        @Test
        @DisplayName("a requestId that is not a UUIDv7 is a 400 (Copilot)")
        void requestIdMustBeVersionSeven() {
            assertThatThrownBy(() -> service.recordCashMovement(petty(UUID.randomUUID(), "10.00", null)))
                    .isInstanceOf(RegisterSessionRequestValidationException.class)
                    .hasMessageContaining("UUIDv7");
            assertThat(recorded).isEmpty();
        }

        @Test
        @DisplayName("ADR-0067: a missing or non-ISO currency is 400, another ISO currency 422; the movement echoes it")
        void currencyRules() {
            assertThatThrownBy(() -> service.recordCashMovement(new CashMovementCommand(
                            SESSION_ID,
                            UUIDv7Generator.generate(),
                            "BANK_DROP",
                            BigDecimal.TEN,
                            null,
                            null,
                            null,
                            "B",
                            null,
                            null,
                            null)))
                    .isInstanceOf(RegisterSessionRequestValidationException.class)
                    .hasMessageContaining("currencyCode");
            assertThatThrownBy(() -> service.recordCashMovement(new CashMovementCommand(
                            SESSION_ID,
                            UUIDv7Generator.generate(),
                            "BANK_DROP",
                            BigDecimal.TEN,
                            "XXZ",
                            null,
                            null,
                            "B",
                            null,
                            null,
                            null)))
                    .isInstanceOf(RegisterSessionRequestValidationException.class);
            assertThatThrownBy(() -> service.recordCashMovement(new CashMovementCommand(
                            SESSION_ID,
                            UUIDv7Generator.generate(),
                            "BANK_DROP",
                            BigDecimal.TEN,
                            "CAD",
                            null,
                            null,
                            "B",
                            null,
                            null,
                            null)))
                    .isInstanceOf(CurrencyNotSupportedException.class);
            assertThat(recorded).isEmpty();

            CashMovementResult drop = service.recordCashMovement(bankDrop(UUIDv7Generator.generate(), "10.00", "B"));
            assertThat(drop.movement().currencyCode()).isEqualTo("USD");
            assertThat(drop.movement().clerkUserId()).isEqualTo(CASHIER_ID);
        }

        @Test
        @DisplayName("M3: a cashier scoped to another shop can neither record nor read the options — nothing recorded")
        void cashierOutOfReachIsDenied() {
            LocationAncestorResolver resolver = id -> new AncestorSets(Set.of(id), Set.of(id));
            signIn(
                    "cashier",
                    LocationScope.of(
                            Set.of(),
                            Set.of("order:session:cash_movement"),
                            Optional.of(Set.of(OTHER_LOCATION)),
                            true,
                            resolver));

            assertThatThrownBy(() -> service.recordCashMovement(bankDrop(UUIDv7Generator.generate(), "10.00", "B")))
                    .isInstanceOf(LocationScopeDeniedException.class);
            assertThatThrownBy(() -> service.cashMovementOptions(SESSION_ID))
                    .isInstanceOf(LocationScopeDeniedException.class);
            assertThat(recorded).isEmpty();
        }

        @Test
        @DisplayName("#2573: a float movement on a drawer whose register float is now held elsewhere is 422, never"
                + " measured against zero")
        void floatAtAnotherLocationRefusesFloatChanges() {
            when(floats.findByRegisterId(TERMINAL))
                    .thenReturn(Optional.of(ExtAccountingRegisterFloat.builder()
                            .registerId(TERMINAL)
                            .locationId(OTHER_LOCATION)
                            .amount(new BigDecimal("250.0000"))
                            .currencyCode("USD")
                            .build()));

            // A decrease of the whole drawer float would match a zero target; it must not.
            assertThatThrownBy(() -> service.recordCashMovement(floatChange("FLOAT_DECREASE", "200.00", "token-1")))
                    .satisfies(e -> {
                        assertThat(refusal(e).refusal()).isEqualTo(Refusal.FLOAT_CHANGE_NOT_RECORDED);
                        assertThat(e.getMessage())
                                .contains("another location")
                                .doesNotContain(OTHER_LOCATION.toString());
                    });
            assertThatThrownBy(() -> service.recordCashMovement(floatChange("FLOAT_INCREASE", "50.00", "token-1")))
                    .satisfies(e -> assertThat(refusal(e).refusal()).isEqualTo(Refusal.FLOAT_CHANGE_NOT_RECORDED));
            assertThat(recorded).isEmpty();
        }

        @Test
        @DisplayName("l7: a FLOAT_DECREASE toward a negative configured float is refused")
        void decreaseTowardNegativeTargetRefused() {
            when(floats.findByRegisterId(TERMINAL))
                    .thenReturn(Optional.of(ExtAccountingRegisterFloat.builder()
                            .registerId(TERMINAL)
                            .locationId(LOCATION)
                            .amount(new BigDecimal("-25.0000"))
                            .currencyCode("USD")
                            .build()));

            assertThatThrownBy(() -> service.recordCashMovement(floatChange("FLOAT_DECREASE", "225.00", "token-1")))
                    .satisfies(e -> assertThat(refusal(e).refusal()).isEqualTo(Refusal.FLOAT_CHANGE_NOT_RECORDED));
        }

        @Test
        @DisplayName("l4: only the requestId key maps to IDEMPOTENCY_CONFLICT; another integrity failure is not hidden")
        void onlyTheRequestIdConstraintIsAnIdempotencyConflict() {
            org.mockito.Mockito.doThrow(
                            new DataIntegrityViolationException(
                                    "violates check constraint cash_movement_reason_code_check"),
                            new DataIntegrityViolationException(
                                    "duplicate key value violates unique constraint \"uq_cash_movement_request\""))
                    .when(movements)
                    .saveAndFlush(any());

            assertThatThrownBy(() -> service.recordCashMovement(bankDrop(UUIDv7Generator.generate(), "10.00", "B")))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> service.recordCashMovement(bankDrop(UUIDv7Generator.generate(), "10.00", "B")))
                    .isInstanceOf(CashMovementIdempotencyConflictException.class);
        }

        @Test
        @DisplayName("l3: the command's string form never carries the approval token")
        void commandToStringHidesToken() {
            assertThat(petty(UUIDv7Generator.generate(), "10.00", "secret-token-value")
                            .toString())
                    .doesNotContain("secret-token-value")
                    .contains("approvalToken=present");
        }
    }

    @Nested
    @DisplayName("round 2 MAJOR-1: the drawer keeps the currency it opened in (ADR-0067 R-2)")
    class DrawerCurrencyStamp {

        /** The drawer opened in CAD; the functional currency has since been reconfigured to USD. */
        @BeforeEach
        void stampedInAnotherCurrency() {
            session.setCurrencyCode("CAD");
        }

        private CashMovementCommand drop(String currency) {
            return new CashMovementCommand(
                    SESSION_ID,
                    UUIDv7Generator.generate(),
                    "BANK_DROP",
                    new BigDecimal("10.00"),
                    currency,
                    null,
                    null,
                    "B",
                    null,
                    null,
                    null);
        }

        @Test
        @DisplayName("movements, options and the close fact use the stamp, never the live configuration")
        void stampWinsOverConfiguration() {
            assertThatThrownBy(() -> service.recordCashMovement(drop("USD")))
                    .isInstanceOf(CurrencyNotSupportedException.class)
                    .hasMessageContaining("CAD");
            assertThat(recorded).isEmpty();

            CashMovementResult drop = service.recordCashMovement(drop("CAD"));
            assertThat(drop.movement().currencyCode()).isEqualTo("CAD");
            assertThat(service.cashMovementOptions(SESSION_ID).currencyCode()).isEqualTo("CAD");

            closeSession();
            ArgumentCaptor<RegisterSessionClosedV1> fact = ArgumentCaptor.forClass(RegisterSessionClosedV1.class);
            verify(publisher).publishRegisterSessionClosed(any(), fact.capture());
            assertThat(fact.getValue().currencyCode()).isEqualTo("CAD");
            assertThat(fact.getValue().movements())
                    .singleElement()
                    .extracting(RegisterSessionClosedV1.Movement::currencyCode)
                    .isEqualTo("CAD");
        }

        @Test
        @DisplayName("a manager token is used in the drawer's currency")
        void tokenIsUsedInTheStamp() {
            when(approvalService.use(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(CashMovementApproval.builder()
                            .approvalId(APPROVAL_ID)
                            .approverUserId(MANAGER_ID)
                            .build());

            service.recordCashMovement(new CashMovementCommand(
                    SESSION_ID,
                    UUIDv7Generator.generate(),
                    "BANK_DROP",
                    new BigDecimal("10.00"),
                    "CAD",
                    null,
                    null,
                    "B",
                    null,
                    null,
                    "token-1"));

            verify(approvalService)
                    .use(
                            eq("token-1"),
                            eq(SESSION_ID),
                            eq(CashMovementReason.BANK_DROP),
                            any(),
                            eq("CAD"),
                            any(),
                            any());
        }

        @Test
        @DisplayName("a policy limit stated in another currency cannot be compared: a manager is needed (fail closed)")
        void foreignPolicyLimitNeedsManager() {
            CashMovementCommand petty = new CashMovementCommand(
                    SESSION_ID,
                    UUIDv7Generator.generate(),
                    "PETTY_EXPENSE",
                    new BigDecimal("1.00"),
                    "CAD",
                    "SHOP_SUPPLIES",
                    null,
                    null,
                    "R-1",
                    "gloves",
                    null);

            assertThatThrownBy(() -> service.recordCashMovement(petty))
                    .isInstanceOf(CashMovementRefusedException.class)
                    .satisfies(e -> assertThat(refusal(e).refusal()).isEqualTo(Refusal.APPROVAL_REQUIRED));
            CashMovementOptions.ReasonOption pettyOption = service.cashMovementOptions(SESSION_ID).reasons().stream()
                    .filter(r -> r.reason().equals("PETTY_EXPENSE"))
                    .findFirst()
                    .orElseThrow();
            assertThat(pettyOption.cashierLimit()).isNull();
            assertThat(pettyOption.alwaysNeedsManager()).isTrue();
        }

        @Test
        @DisplayName("a tolerance stated in another currency is zero at close: any difference needs approve_variance")
        void foreignToleranceIsZeroAtClose() {
            session.setStatus(RegisterSessionStatus.CLOSING);
            session.setCountedCash(new BigDecimal("201.0000"));
            when(salesOrders.existsBySessionIdAndStatus(SESSION_ID, SalesOrderStatus.PENDING_PAYMENT))
                    .thenReturn(false);
            when(payments.findBySessionId(SESSION_ID)).thenReturn(List.of());
            when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // 1.00 over is inside the policy's 5.00 USD tolerance, but the drawer counts CAD.
            assertThatThrownBy(() -> service.confirmClose(SESSION_ID))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

            session.setCurrencyCode("USD");
            assertThat(service.confirmClose(SESSION_ID).status()).isEqualTo("CLOSED");
        }
    }

    private void closeSession() {
        session.setStatus(RegisterSessionStatus.CLOSING);
        session.setCountedCash(new BigDecimal("0.0000"));
        when(salesOrders.existsBySessionIdAndStatus(SESSION_ID, SalesOrderStatus.PENDING_PAYMENT))
                .thenReturn(false);
        when(payments.findBySessionId(SESSION_ID)).thenReturn(List.of());
        when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var closer = new UsernamePasswordAuthenticationToken(
                "closer",
                "n/a",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "order:session:approve_variance")));
        closer.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "closer"));
        SecurityContextHolder.getContext().setAuthentication(closer);
        service.confirmClose(SESSION_ID);
    }
}
