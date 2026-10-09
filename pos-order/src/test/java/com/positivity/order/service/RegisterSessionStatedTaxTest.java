package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.order.internal.client.TaxPlausibilityPort;
import com.positivity.order.internal.config.FunctionalCurrency;
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.controller.RegisterSessionExceptionHandler;
import com.positivity.order.internal.dto.CashMovementRequest;
import com.positivity.order.internal.entity.CashMovement;
import com.positivity.order.internal.entity.CashMovementApproval;
import com.positivity.order.internal.entity.CashMovementReason;
import com.positivity.order.internal.entity.CashMovementStatedTax;
import com.positivity.order.internal.entity.ExtAccountingPettyExpenseCategory;
import com.positivity.order.internal.entity.ExtLocation;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.exception.CashMovementIdempotencyConflictException;
import com.positivity.order.internal.exception.CashMovementTaxRefusedException;
import com.positivity.order.internal.exception.RegisterSessionRequestValidationException;
import com.positivity.order.internal.exception.TaxCheckUnavailableException;
import com.positivity.order.internal.repository.CashMovementRepository;
import com.positivity.order.internal.repository.CashMovementStatedTaxRepository;
import com.positivity.order.internal.repository.ExtAccountingPettyExpenseCategoryRepository;
import com.positivity.order.internal.repository.ExtAccountingRegisterFloatRepository;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import com.positivity.order.internal.repository.ExtLocationRepository;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.service.model.CashMovementCommand;
import com.positivity.order.internal.service.model.CashMovementOptions;
import com.positivity.order.internal.service.model.CashMovementResult;
import com.positivity.order.internal.service.model.SessionPolicyView;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationScope;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CAP:550 S32d (#2639) section B: stated tax on a petty expense, the offered regimes, the local checks, pos-tax's
 * plausibility answers, the binding call order, replay and the no-echo rules. The examples use placeholder CAD data;
 * no assertion depends on Canada beyond it.
 */
@DisplayName("RegisterSessionServiceImpl — stated tax on drawer expenses (CAP:550 S32d)")
class RegisterSessionStatedTaxTest {

    private static final UUID SESSION_ID = UUID.fromString("01900000-0000-7000-8000-00000000a011");
    private static final UUID LOCATION = UUID.fromString("01900000-0000-7000-8000-00000000d011");
    private static final UUID MANAGER_ID = UUID.fromString("01900000-0000-7000-8000-00000000b011");
    private static final UUID CASHIER_ID = UUID.fromString("01900000-0000-7000-8000-00000000e011");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final String NUMBER = "000000000RT0001";
    private static final String SUPPLIER = "Corner Hardware Sole Prop";

    private final RegisterSessionRepository sessions = mock(RegisterSessionRepository.class);
    private final CashMovementRepository movements = mock(CashMovementRepository.class);
    private final CashMovementStatedTaxRepository statedTaxRows = mock(CashMovementStatedTaxRepository.class);
    private final SessionPolicyService policyService = mock(SessionPolicyService.class);
    private final CashMovementApprovalService approvalService = mock(CashMovementApprovalService.class);
    private final ExtAccountingPettyExpenseCategoryRepository categories =
            mock(ExtAccountingPettyExpenseCategoryRepository.class);
    private final ExtLocationRepository locations = mock(ExtLocationRepository.class);
    private final OrderDomainEventPublisher publisher = mock(OrderDomainEventPublisher.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final FakeRegistrations registrations = new FakeRegistrations();
    private final FakeTaxPort taxPort = new FakeTaxPort();

    private final List<CashMovement> recorded = new ArrayList<>();
    private final List<CashMovementStatedTax> recordedTaxes = new ArrayList<>();

    private RegisterSession session;
    private ExtAccountingPettyExpenseCategory shopSupplies;
    private RegisterSessionServiceImpl service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
        when(meters.getIfAvailable()).thenReturn(meterRegistry);
        DrawerStatedTax drawerStatedTax = new DrawerStatedTax(locations, registrations, statedTaxRows, taxPort, meters);
        service = new RegisterSessionServiceImpl(
                sessions,
                movements,
                mock(SalesOrderRepository.class),
                mock(OrderPaymentRecordRepository.class),
                publisher,
                new HouseAccountReplica(mock(ExtCustomerRepository.class)),
                policyService,
                approvalService,
                mock(ExtAccountingRegisterFloatRepository.class),
                categories,
                new FunctionalCurrency("CAD"),
                drawerStatedTax,
                CLOCK,
                meters);
        session = RegisterSession.builder()
                .sessionId(SESSION_ID)
                .version(1L)
                .terminalId("T-1")
                .locationId(LOCATION)
                .openedByClerkId("opener")
                .status(RegisterSessionStatus.OPEN)
                .currencyCode("CAD")
                .openingFloat(new BigDecimal("200.0000"))
                .openedAt(Instant.parse("2026-10-15T08:00:00Z"))
                .build();
        lenient().when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
        lenient().when(sessions.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(session));
        lenient().when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient()
                .when(policyService.current())
                .thenReturn(new SessionPolicyView(
                        1L, true, new BigDecimal("500.00"), true, null, new BigDecimal("5.00"), "CAD"));
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
        lenient().when(statedTaxRows.save(any())).thenAnswer(inv -> {
            CashMovementStatedTax row = inv.getArgument(0);
            recordedTaxes.add(row);
            return row;
        });
        lenient().when(statedTaxRows.findByMovementIdInOrderByRegimeAsc(any())).thenAnswer(inv -> {
            java.util.Collection<?> ids = inv.getArgument(0);
            return recordedTaxes.stream()
                    .filter(row -> ids.contains(row.getMovementId()))
                    .sorted(java.util.Comparator.comparing(CashMovementStatedTax::getRegime))
                    .toList();
        });
        shopSupplies = category("SHOP_SUPPLIES", true);
        lenient().when(categories.findByCode("SHOP_SUPPLIES")).thenReturn(Optional.of(shopSupplies));
        lenient()
                .when(categories.findByStatusOrderByCodeAsc(ExtAccountingPettyExpenseCategory.ACTIVE))
                .thenAnswer(_ -> List.of(shopSupplies, category("FUEL", false)));
        at("CA", "QC");
        registrations.add("CA", "GST_HST", "CA", LocalDate.of(2026, 1, 1), null);
        registrations.add("CA", "QST", "QC", LocalDate.of(2026, 1, 1), null);
        taxPort.answer = new TaxPlausibilityPort.Checked("PLAUSIBLE", true, Boolean.TRUE);
        signIn();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private static void signIn() {
        var token = new UsernamePasswordAuthenticationToken("cashier", "n/a", List.of());
        token.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                "cashier",
                GatewaySecurityConstants.DETAIL_USER_ID,
                CASHIER_ID,
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                LocationScope.unscoped()));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private void at(String country, String region) {
        lenient()
                .when(locations.findById(LOCATION))
                .thenReturn(Optional.of(ExtLocation.builder()
                        .locationId(LOCATION)
                        .active(true)
                        .country(country)
                        .region(region)
                        .postalCode("Z1Z 1Z1")
                        .city("Springfield")
                        .aggregateVersion(1L)
                        .syncedAt(Instant.now(CLOCK))
                        .build()));
    }

    private static ExtAccountingPettyExpenseCategory category(String code, boolean recoverable) {
        return ExtAccountingPettyExpenseCategory.builder()
                .pettyExpenseCategoryId(UUIDv7Generator.generate())
                .code(code)
                .label(code)
                .status("ACTIVE")
                .taxRecoverable(recoverable)
                .recoverablePercent(recoverable ? new BigDecimal("100.00") : null)
                .aggregateVersion(1L)
                .syncedAt(Instant.now(CLOCK))
                .build();
    }

    private static CashMovementCommand.StatedTax tax(String regime, String amount) {
        return new CashMovementCommand.StatedTax(regime, amount == null ? null : new BigDecimal(amount));
    }

    private static CashMovementCommand petty(
            UUID requestId,
            String amount,
            String token,
            String supplierName,
            List<CashMovementCommand.StatedTax> taxes,
            String number) {
        return new CashMovementCommand(
                SESSION_ID,
                requestId,
                "PETTY_EXPENSE",
                new BigDecimal(amount),
                "CAD",
                "SHOP_SUPPLIES",
                null,
                null,
                "R-100",
                "towels",
                token,
                supplierName,
                taxes,
                number);
    }

    private static CashMovementCommand petty(String amount, List<CashMovementCommand.StatedTax> taxes, String number) {
        return petty(UUIDv7Generator.generate(), amount, null, SUPPLIER, taxes, number);
    }

    private void managerApproves() {
        lenient()
                .when(approvalService.use(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CashMovementApproval.builder()
                        .approvalId(UUIDv7Generator.generate())
                        .approverUserId(MANAGER_ID)
                        .amount(new BigDecimal("40.00"))
                        .build());
    }

    private static List<String> fields(Throwable thrown) {
        List<ApiError.FieldError> errors =
                switch (thrown) {
                    case CashMovementTaxRefusedException e -> e.fieldErrors();
                    case RegisterSessionRequestValidationException e -> e.fieldErrors();
                    default -> List.of();
                };
        return errors.stream().map(ApiError.FieldError::field).toList();
    }

    private static CashMovementTaxRefusedException.Code code(Throwable thrown) {
        return ((CashMovementTaxRefusedException) thrown).code();
    }

    /** pos-tax as the drawer sees it: one canned answer, and the calls it received. */
    static final class FakeTaxPort implements TaxPlausibilityPort {
        PlausibilityAnswer answer;
        Optional<EvidenceThreshold> evidence = Optional.empty();
        RuntimeException evidenceFailure;
        final List<PlausibilityQuery> calls = new ArrayList<>();

        @Override
        public @NonNull PlausibilityAnswer check(@NonNull PlausibilityQuery query) {
            calls.add(query);
            return answer;
        }

        @Override
        public @NonNull Optional<EvidenceThreshold> drawerEvidenceRule(
                @NonNull String countryCode, @NonNull LocalDate asOf) {
            if (evidenceFailure != null) {
                throw evidenceFailure;
            }
            return evidence;
        }
    }

    /** The {@code ext_tax_registration} copy: as-of reads with both ends inclusive. */
    static final class FakeRegistrations implements TaxRegistrationReplica {
        final List<Registration> rows = new ArrayList<>();

        void add(String country, String regime, String jurisdiction, LocalDate from, LocalDate to) {
            rows.add(new Registration(UUIDv7Generator.generate(), country, regime, jurisdiction, from, to));
        }

        @Override
        public @NonNull Optional<Registration> inEffectOn(
                @NonNull String countryCode, @NonNull String regime, @NonNull LocalDate businessDate) {
            return inEffectFor(countryCode, businessDate).stream()
                    .filter(r -> r.regime().equals(regime))
                    .findFirst();
        }

        @Override
        public @NonNull List<Registration> inEffectFor(@NonNull String countryCode, @NonNull LocalDate businessDate) {
            return rows.stream()
                    .filter(r -> r.countryCode().equals(countryCode))
                    .filter(r -> !r.effectiveFrom().isAfter(businessDate)
                            && (r.effectiveTo() == null || !r.effectiveTo().isBefore(businessDate)))
                    .sorted(java.util.Comparator.comparing(Registration::regime))
                    .toList();
        }
    }

    // ── AC 18: the regimes offered ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 18 / A1: a regime is offered only from local replicas")
    class Offered {

        @Test
        @DisplayName("a QC location offers GST_HST and QST; an ON location offers GST_HST only")
        void regionDecidesTheRegimes() {
            CashMovementOptions qc = service.cashMovementOptions(SESSION_ID);
            assertThat(qc.categories())
                    .filteredOn(c -> c.code().equals("SHOP_SUPPLIES"))
                    .singleElement()
                    .satisfies(c -> assertThat(c.offeredRegimes()).containsExactly("GST_HST", "QST"));
            assertThat(qc.categories())
                    .filteredOn(c -> c.code().equals("FUEL"))
                    .singleElement()
                    .satisfies(c -> assertThat(c.offeredRegimes()).isEmpty());

            at("CA", "ON");
            assertThat(service.cashMovementOptions(SESSION_ID).categories())
                    .filteredOn(c -> c.code().equals("SHOP_SUPPLIES"))
                    .singleElement()
                    .satisfies(c -> assertThat(c.offeredRegimes()).containsExactly("GST_HST"));
        }

        @Test
        @DisplayName("[M] a QST amount stated at ON → 422 TAX_REGIME_NOT_OFFERED on statedTaxes[0].regime")
        void qstAtOntarioIsNotOffered() {
            at("CA", "ON");

            assertThatThrownBy(() -> service.recordCashMovement(petty("40.00", List.of(tax("QST", "2.00")), null)))
                    .isInstanceOf(CashMovementTaxRefusedException.class)
                    .satisfies(e -> {
                        assertThat(code(e)).isEqualTo(CashMovementTaxRefusedException.Code.TAX_REGIME_NOT_OFFERED);
                        assertThat(fields(e)).containsExactly("statedTaxes[0].regime");
                    });
            assertThat(recorded).isEmpty();
            assertThat(taxPort.calls).isEmpty();
        }

        static Stream<Arguments> notOffered() {
            return Stream.of(
                    Arguments.of("category not recoverable", "category"),
                    Arguments.of("no registration on the movement's date", "starts-tomorrow"),
                    Arguments.of("the registration ended the day before", "ended-yesterday"),
                    Arguments.of("the session has no location", "no-location"),
                    Arguments.of("a USD drawer with a CA registration (currency guard)", "usd-drawer"),
                    Arguments.of("an unknown regime code", "unknown-regime"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("notOffered")
        @DisplayName("each missing condition → 422 TAX_REGIME_NOT_OFFERED, and offeredRegimes is empty")
        void eachConditionIsRequired(String label, String scenario) {
            String regime = "GST_HST";
            String currency = "CAD";
            switch (scenario) {
                case "category" -> shopSupplies.setTaxRecoverable(false);
                case "starts-tomorrow" -> {
                    registrations.rows.clear();
                    registrations.add("CA", "GST_HST", "CA", TODAY.plusDays(1), null);
                }
                case "ended-yesterday" -> {
                    registrations.rows.clear();
                    registrations.add("CA", "GST_HST", "CA", LocalDate.of(2026, 1, 1), TODAY.minusDays(1));
                }
                case "no-location" -> session.setLocationId(null);
                case "usd-drawer" -> {
                    currency = "USD";
                    session.setCurrencyCode("USD");
                    when(policyService.current())
                            .thenReturn(new SessionPolicyView(
                                    1L, true, new BigDecimal("500.00"), true, null, new BigDecimal("5.00"), "USD"));
                }
                case "unknown-regime" -> regime = "NOT_A_REGIME";
                default -> throw new IllegalArgumentException(scenario);
            }
            String drawerCurrency = currency;
            String stated = regime;

            assertThatThrownBy(() -> service.recordCashMovement(new CashMovementCommand(
                            SESSION_ID,
                            UUIDv7Generator.generate(),
                            "PETTY_EXPENSE",
                            new BigDecimal("40.00"),
                            drawerCurrency,
                            "SHOP_SUPPLIES",
                            null,
                            null,
                            "R-1",
                            "note",
                            null,
                            SUPPLIER,
                            List.of(tax(stated, "4.60")),
                            null)))
                    .isInstanceOf(CashMovementTaxRefusedException.class)
                    .satisfies(e -> {
                        assertThat(code(e)).isEqualTo(CashMovementTaxRefusedException.Code.TAX_REGIME_NOT_OFFERED);
                        assertThat(fields(e)).containsExactly("statedTaxes[0].regime");
                    });
            assertThat(recorded).isEmpty();
            assertThat(taxPort.calls).isEmpty();
            if (!scenario.equals("unknown-regime")) {
                assertThat(service.cashMovementOptions(SESSION_ID).categories())
                        .allSatisfy(c -> assertThat(c.offeredRegimes()).doesNotContain("GST_HST"));
            }
        }

        @Test
        @DisplayName("AC 3: a registration from the 15th offers nothing on the 14th, and GST_HST on the 15th")
        void registrationDateIsInclusive() {
            registrations.rows.clear();
            registrations.add("CA", "GST_HST", "CA", TODAY, null);
            DrawerStatedTax offer =
                    new DrawerStatedTax(locations, registrations, statedTaxRows, taxPort, emptyMeters());

            assertThat(offer.offeredRegimes(session, shopSupplies, TODAY.minusDays(1)))
                    .isEmpty();
            assertThat(offer.offeredRegimes(session, shopSupplies, TODAY)).containsExactly("GST_HST");
        }

        @Test
        @DisplayName("AC 1: a USD drawer offers nothing, and a request without the new fields is unchanged")
        void usdDrawerOffersNothing() {
            registrations.rows.clear();
            session.setCurrencyCode("USD");
            at("US", "TX");
            when(policyService.current())
                    .thenReturn(new SessionPolicyView(
                            1L, true, new BigDecimal("500.00"), true, null, new BigDecimal("5.00"), "USD"));

            CashMovementOptions options = service.cashMovementOptions(SESSION_ID);
            assertThat(options.categories())
                    .allSatisfy(c -> assertThat(c.offeredRegimes()).isEmpty());
            assertThat(options.evidenceRule()).isNull();

            CashMovementResult result = service.recordCashMovement(new CashMovementCommand(
                    SESSION_ID,
                    UUIDv7Generator.generate(),
                    "PETTY_EXPENSE",
                    new BigDecimal("30.00"),
                    "USD",
                    "SHOP_SUPPLIES",
                    null,
                    null,
                    "R-1",
                    "gloves",
                    null,
                    null,
                    null,
                    null));
            assertThat(result.movement().statedTaxes()).isEmpty();
            assertThat(result.movement().taxPlausibility()).isNull();
            assertThat(result.movement().supplierRegistrationRequired()).isNull();
            assertThat(taxPort.calls).isEmpty();
        }
    }

    // ── AC 22: shape ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 22 / A2: shape errors → 400 REGISTER_SESSION_INVALID_ARGUMENT with fieldErrors")
    class Shape {

        static Stream<Arguments> malformed() {
            return Stream.of(
                    Arguments.of(
                            "a duplicate regime",
                            List.of(tax("GST_HST", "1.00"), tax("GST_HST", "2.00")),
                            SUPPLIER,
                            null,
                            "statedTaxes[1].regime"),
                    Arguments.of(
                            "a zero amount", List.of(tax("GST_HST", "0")), SUPPLIER, null, "statedTaxes[0].amount"),
                    Arguments.of(
                            "a negative amount",
                            List.of(tax("GST_HST", "-1.00")),
                            SUPPLIER,
                            null,
                            "statedTaxes[0].amount"),
                    Arguments.of(
                            "a missing amount", List.of(tax("GST_HST", null)), SUPPLIER, null, "statedTaxes[0].amount"),
                    Arguments.of(
                            "a lower-case regime",
                            List.of(tax("gst_hst", "1.00")),
                            SUPPLIER,
                            null,
                            "statedTaxes[0].regime"),
                    Arguments.of(
                            "a regime of 33 characters",
                            List.of(tax("A".repeat(33), "1.00")),
                            SUPPLIER,
                            null,
                            "statedTaxes[0].regime"),
                    Arguments.of(
                            "a number without a stated amount",
                            List.of(),
                            SUPPLIER,
                            NUMBER,
                            "supplierRegistrationNumber"),
                    Arguments.of(
                            "stated tax without supplierName",
                            List.of(tax("GST_HST", "1.00")),
                            null,
                            null,
                            "supplierName"),
                    Arguments.of(
                            "a supplierName over 200 characters",
                            List.of(tax("GST_HST", "1.00")),
                            "x".repeat(201),
                            null,
                            "supplierName"),
                    Arguments.of(
                            "a number longer than 32 characters after normalising",
                            List.of(tax("GST_HST", "1.00")),
                            SUPPLIER,
                            "0".repeat(33),
                            "supplierRegistrationNumber"),
                    Arguments.of(
                            "a number with an interior tab",
                            List.of(tax("GST_HST", "1.00")),
                            SUPPLIER,
                            "000000000\tRT0001",
                            "supplierRegistrationNumber"),
                    Arguments.of(
                            "a number with an interior no-break space",
                            List.of(tax("GST_HST", "1.00")),
                            SUPPLIER,
                            "000000000 RT0001",
                            "supplierRegistrationNumber"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("malformed")
        void malformedIsRefusedWithoutPosTax(
                String label,
                List<CashMovementCommand.StatedTax> taxes,
                String supplierName,
                String number,
                String field) {
            assertThatThrownBy(() -> service.recordCashMovement(
                            petty(UUIDv7Generator.generate(), "40.00", null, supplierName, taxes, number)))
                    .isInstanceOf(RegisterSessionRequestValidationException.class)
                    .satisfies(e -> assertThat(fields(e)).contains(field));
            assertThat(recorded).isEmpty();
            assertThat(taxPort.calls).isEmpty();
        }

        @ParameterizedTest(name = "{0} on a non-petty reason")
        @ValueSource(strings = {"statedTaxes", "supplierName", "supplierRegistrationNumber"})
        @DisplayName("the three fields on a non-petty reason → 400")
        void fieldsOnANonPettyReason(String field) {
            CashMovementCommand drop = new CashMovementCommand(
                    SESSION_ID,
                    UUIDv7Generator.generate(),
                    "BANK_DROP",
                    new BigDecimal("100.00"),
                    "CAD",
                    null,
                    null,
                    "BAG-1",
                    null,
                    null,
                    null,
                    field.equals("supplierName") ? SUPPLIER : null,
                    field.equals("statedTaxes") ? List.of(tax("GST_HST", "1.00")) : null,
                    field.equals("supplierRegistrationNumber") ? NUMBER : null);

            assertThatThrownBy(() -> service.recordCashMovement(drop))
                    .isInstanceOf(RegisterSessionRequestValidationException.class)
                    .satisfies(e -> assertThat(fields(e)).containsExactly(field));
        }

        @Test
        @DisplayName("an absent statedTaxes and [] mean the same: no stated tax, no pos-tax call")
        void absentAndEmptyAreTheSame() {
            CashMovementResult absent = service.recordCashMovement(petty("30.00", null, null));
            CashMovementResult empty = service.recordCashMovement(petty("31.00", List.of(), null));

            assertThat(absent.movement().statedTaxes()).isEmpty();
            assertThat(empty.movement().statedTaxes()).isEmpty();
            assertThat(taxPort.calls).isEmpty();
        }
    }

    // ── AC 25: precision and bound keys ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 25: precision is 422 AMOUNT_PRECISION_EXCEEDS_CURRENCY, never rounded")
    class Precision {

        @Test
        @DisplayName("[M] a CAD stated amount of 4.605 → 422 on statedTaxes[0].amount; nothing recorded, no call")
        void statedAmountTooPrecise() {
            managerApproves();

            assertThatThrownBy(() -> service.recordCashMovement(petty(
                            UUIDv7Generator.generate(),
                            "40.00",
                            "token-1",
                            SUPPLIER,
                            List.of(tax("GST_HST", "4.605")),
                            null)))
                    .isInstanceOf(CashMovementTaxRefusedException.class)
                    .satisfies(e -> {
                        assertThat(code(e))
                                .isEqualTo(CashMovementTaxRefusedException.Code.AMOUNT_PRECISION_EXCEEDS_CURRENCY);
                        assertThat(fields(e)).containsExactly("statedTaxes[0].amount");
                    });
            assertThat(recorded).isEmpty();
            assertThat(taxPort.calls).isEmpty();
            verify(approvalService, never()).use(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("[M] a CAD movement of 40.005, on any reason → 422 on amount; not rounded, not recorded")
        void movementAmountTooPrecise() {
            CashMovementCommand drop = new CashMovementCommand(
                    SESSION_ID,
                    UUIDv7Generator.generate(),
                    "BANK_DROP",
                    new BigDecimal("40.005"),
                    "CAD",
                    null,
                    null,
                    "BAG-1",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.recordCashMovement(drop))
                    .isInstanceOf(CashMovementTaxRefusedException.class)
                    .satisfies(e -> assertThat(fields(e)).containsExactly("amount"));
            assertThat(recorded).isEmpty();
        }

        @Test
        @DisplayName("40.005 with a stated 4.605 → one 422 naming amount and statedTaxes[0].amount")
        void bothInOneRefusal() {
            assertThatThrownBy(
                            () -> service.recordCashMovement(petty("40.005", List.of(tax("GST_HST", "4.605")), null)))
                    .isInstanceOf(CashMovementTaxRefusedException.class)
                    .satisfies(e -> assertThat(fields(e)).containsExactly("amount", "statedTaxes[0].amount"));
            assertThat(taxPort.calls).isEmpty();
        }

        @Test
        @DisplayName("40.000 and 4.600 pass: trailing zeros do not count, and they are stated at the exponent")
        void trailingZerosDoNotCount() {
            CashMovementResult result =
                    service.recordCashMovement(petty("40.000", List.of(tax("GST_HST", "4.600")), null));

            assertThat(result.movement().amount()).isEqualTo(new BigDecimal("40.00"));
            assertThat(result.movement().statedTaxes())
                    .singleElement()
                    .satisfies(t -> assertThat(t.amount()).isEqualTo(new BigDecimal("4.60")));
            assertThat(recorded.getFirst().getAmount()).isEqualTo(new BigDecimal("40.00"));
        }

        @Test
        @DisplayName("a JPY drawer refuses a stated amount with one decimal")
        void jpyHasNoDecimals() {
            session.setCurrencyCode("JPY");

            assertThatThrownBy(() -> service.recordCashMovement(new CashMovementCommand(
                            SESSION_ID,
                            UUIDv7Generator.generate(),
                            "PETTY_EXPENSE",
                            new BigDecimal("4000"),
                            "JPY",
                            "SHOP_SUPPLIES",
                            null,
                            null,
                            "R-1",
                            "note",
                            null,
                            SUPPLIER,
                            List.of(tax("GST_HST", "460.5")),
                            null)))
                    .isInstanceOf(CashMovementTaxRefusedException.class)
                    .satisfies(e -> {
                        assertThat(code(e))
                                .isEqualTo(CashMovementTaxRefusedException.Code.AMOUNT_PRECISION_EXCEEDS_CURRENCY);
                        assertThat(fields(e)).containsExactly("statedTaxes[0].amount");
                    });
        }

        @Test
        @DisplayName("[M] precision answers 422, never the 400 of a shape error")
        void precisionIsNotAShapeError() {
            assertThatThrownBy(() -> service.recordCashMovement(petty("40.005", null, null)))
                    .isInstanceOf(CashMovementTaxRefusedException.class)
                    .isNotInstanceOf(RegisterSessionRequestValidationException.class);
        }
    }

    // ── AC 21 / 25: the local arithmetic bound ───────────────────────────────────────────────

    @Nested
    @DisplayName("AC 21 / 25: the local bound answers 422 TAX_AMOUNT_IMPLAUSIBLE before pos-tax is asked")
    class LocalBound {

        @ParameterizedTest(name = "pos-tax up: {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("two amounts each below T but summing to T or more → fieldErrors[statedTaxes]; no call")
        void sumAtOrAboveTotal(boolean posTaxUp) {
            if (!posTaxUp) {
                taxPort.answer = new TaxPlausibilityPort.Unavailable();
            }

            assertThatThrownBy(() -> service.recordCashMovement(
                            petty("40.00", List.of(tax("GST_HST", "20.00"), tax("QST", "20.00")), null)))
                    .isInstanceOf(CashMovementTaxRefusedException.class)
                    .satisfies(e -> {
                        assertThat(code(e)).isEqualTo(CashMovementTaxRefusedException.Code.TAX_AMOUNT_IMPLAUSIBLE);
                        assertThat(fields(e)).containsExactly("statedTaxes");
                    });
            assertThat(taxPort.calls).isEmpty();
        }

        @Test
        @DisplayName("one amount at or above T → fieldErrors[statedTaxes[0].amount] only")
        void oneAmountAtTotal() {
            assertThatThrownBy(() -> service.recordCashMovement(petty("40.00", List.of(tax("GST_HST", "40.00")), null)))
                    .isInstanceOf(CashMovementTaxRefusedException.class)
                    .satisfies(e -> assertThat(fields(e)).containsExactly("statedTaxes[0].amount"));
            assertThat(taxPort.calls).isEmpty();
        }
    }

    // ── AC 16, 17, 23: pos-tax's answers ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 16, 17, 23: pos-tax's answers, keyed on code")
    class Answers {

        @Test
        @DisplayName("AC 5 data: 200 plausible with a well-formed number → recorded with the outcome and the flag")
        void plausibleIsRecorded() {
            CashMovementResult result = service.recordCashMovement(
                    petty("40.00", List.of(tax("GST_HST", "4.60")), " 000 000 000-rt-0001 "));

            CashMovement row = recorded.getFirst();
            assertThat(row.getSupplierRegistrationNumber()).isEqualTo(NUMBER);
            assertThat(row.getTaxPlausibility()).isEqualTo("PLAUSIBLE");
            assertThat(row.getSupplierRegistrationRequired()).isTrue();
            assertThat(row.getSupplierName()).isEqualTo(SUPPLIER);
            assertThat(result.movement().supplierRegistrationNumberProvided()).isTrue();
            assertThat(result.movement().statedTaxes())
                    .singleElement()
                    .satisfies(t -> assertThat(t.regime()).isEqualTo("GST_HST"));
            assertThat(recordedTaxes)
                    .singleElement()
                    .satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo("4.60"));
            TaxPlausibilityPort.PlausibilityQuery query = taxPort.calls.getFirst();
            assertThat(query.countryCode()).isEqualTo("CA");
            assertThat(query.regionCode()).isEqualTo("QC");
            assertThat(query.asOf()).isEqualTo(TODAY);
            assertThat(query.receiptTotal()).isEqualByComparingTo("40.00");
            assertThat(query.supplierRegistrationNumber()).isEqualTo(NUMBER);
        }

        @Test
        @DisplayName("AC 16: 000 000 000 rt 0001 is recorded as 000000000RT0001")
        void numberIsStoredNormalised() {
            service.recordCashMovement(petty("40.00", List.of(tax("GST_HST", "4.60")), "000 000 000 rt 0001"));

            assertThat(recorded.getFirst().getSupplierRegistrationNumber()).isEqualTo(NUMBER);
        }

        @Test
        @DisplayName("[M] AC 16: well-formed false → 400 on supplierRegistrationNumber, nothing recorded, token unused")
        void malformedNumberIsRefused() {
            managerApproves();
            taxPort.answer = new TaxPlausibilityPort.Checked("PLAUSIBLE", true, Boolean.FALSE);

            assertThatThrownBy(() -> service.recordCashMovement(petty(
                            UUIDv7Generator.generate(),
                            "40.00",
                            "token-1",
                            SUPPLIER,
                            List.of(tax("GST_HST", "4.60")),
                            "000000000")))
                    .isInstanceOf(RegisterSessionRequestValidationException.class)
                    .satisfies(e -> {
                        assertThat(fields(e)).containsExactly("supplierRegistrationNumber");
                        assertThat(e.getMessage()).doesNotContain("000000000");
                    });
            assertThat(recorded).isEmpty();
            verify(approvalService, never()).use(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("AC 23: well-formed null with a number → 422 SUPPLIER_REGISTRATION_NOT_ACCEPTED")
        void noSupplierRegimeRefusesTheNumber() {
            taxPort.answer = new TaxPlausibilityPort.Checked("PLAUSIBLE", false, null);

            assertThatThrownBy(
                            () -> service.recordCashMovement(petty("40.00", List.of(tax("GST_HST", "4.60")), NUMBER)))
                    .isInstanceOf(CashMovementTaxRefusedException.class)
                    .satisfies(e -> assertThat(code(e))
                            .isEqualTo(CashMovementTaxRefusedException.Code.SUPPLIER_REGISTRATION_NOT_ACCEPTED));
            assertThat(recorded).isEmpty();
        }

        @Test
        @DisplayName("without a number, a 200 is recorded with its outcome and flag")
        void noNumberIsRecorded() {
            taxPort.answer = new TaxPlausibilityPort.Checked("RATE_UNAVAILABLE", false, null);

            service.recordCashMovement(petty("40.00", List.of(tax("GST_HST", "4.60")), null));

            assertThat(recorded.getFirst().getTaxPlausibility()).isEqualTo("RATE_UNAVAILABLE");
            assertThat(recorded.getFirst().getSupplierRegistrationRequired()).isFalse();
            assertThat(recorded.getFirst().getSupplierRegistrationNumber()).isNull();
        }

        @Test
        @DisplayName("a missing required number is recorded, not refused")
        void missingRequiredNumberIsRecorded() {
            taxPort.answer = new TaxPlausibilityPort.Checked("PLAUSIBLE", true, null);

            service.recordCashMovement(petty("150.00", List.of(tax("GST_HST", "7.14")), null));

            assertThat(recorded.getFirst().getSupplierRegistrationRequired()).isTrue();
        }

        @ParameterizedTest(name = "with a number: {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("AC 4: 422 TAX_AMOUNT_IMPLAUSIBLE is relayed with pos-tax's field errors")
        void implausibleIsRelayed(boolean withNumber) {
            taxPort.answer = new TaxPlausibilityPort.Implausible(
                    "A stated amount is implausible",
                    List.of(new ApiError.FieldError("statedTaxes[0].amount", "at most 5.21")));

            assertThatThrownBy(() -> service.recordCashMovement(
                            petty("40.00", List.of(tax("GST_HST", "9.00")), withNumber ? NUMBER : null)))
                    .isInstanceOf(CashMovementTaxRefusedException.class)
                    .satisfies(e -> {
                        assertThat(code(e)).isEqualTo(CashMovementTaxRefusedException.Code.TAX_AMOUNT_IMPLAUSIBLE);
                        assertThat(((CashMovementTaxRefusedException) e).fieldErrors())
                                .containsExactly(new ApiError.FieldError("statedTaxes[0].amount", "at most 5.21"));
                    });
            assertThat(recorded).isEmpty();
        }

        static Stream<Arguments> disagreements() {
            return Stream.of(
                    Arguments.of(422, "AMOUNT_PRECISION_EXCEEDS_CURRENCY", "AMOUNT_PRECISION_EXCEEDS_CURRENCY"),
                    Arguments.of(422, "CURRENCY_NOT_SUPPORTED", "CURRENCY_NOT_SUPPORTED"),
                    Arguments.of(422, "TAX_JURISDICTION_NOT_CONFIGURED", "TAX_JURISDICTION_NOT_CONFIGURED"),
                    Arguments.of(422, "TAX_REGIME_NOT_DECLARED", "TAX_REGIME_NOT_DECLARED"),
                    Arguments.of(422, "SOMETHING_NEW", "OTHER"),
                    Arguments.of(422, null, "OTHER"),
                    Arguments.of(400, "VALIDATION_ERROR", "VALIDATION_ERROR"),
                    Arguments.of(404, "NOT_FOUND", "OTHER"),
                    Arguments.of(409, "VALIDATION_ERROR", "OTHER"));
        }

        @ParameterizedTest(name = "{0} {1} with a number → 503, tag {2}")
        @MethodSource("disagreements")
        @DisplayName("[M] AC 17: a disagreement with a number → 503 TAX_CHECK_UNAVAILABLE, counted with a bounded tag")
        void disagreementWithNumber(int status, String code, String tag) {
            managerApproves();
            taxPort.answer = new TaxPlausibilityPort.Disagreement(status, code);

            assertThatThrownBy(() -> service.recordCashMovement(petty(
                            UUIDv7Generator.generate(),
                            "40.00",
                            "token-1",
                            SUPPLIER,
                            List.of(tax("GST_HST", "4.60")),
                            NUMBER)))
                    .isInstanceOf(TaxCheckUnavailableException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(NUMBER));
            assertThat(recorded).isEmpty();
            verify(approvalService, never()).use(any(), any(), any(), any(), any(), any(), any());
            assertThat(meterRegistry
                            .get(DrawerStatedTax.DISAGREEMENT_COUNTER)
                            .tag("code", tag)
                            .counter()
                            .count())
                    .isEqualTo(1.0);
            assertThat(meterRegistry.find(DrawerStatedTax.DISAGREEMENT_COUNTER).counters())
                    .allSatisfy(c -> assertThat(c.getId().getTags())
                            .singleElement()
                            .satisfies(t -> assertThat(t.getKey()).isEqualTo("code")));
        }

        @ParameterizedTest(name = "{0} {1} without a number → recorded RATE_UNAVAILABLE")
        @MethodSource("disagreements")
        @DisplayName("AC 17: a disagreement without a number → recorded RATE_UNAVAILABLE, required null")
        void disagreementWithoutNumber(int status, String code, String tag) {
            taxPort.answer = new TaxPlausibilityPort.Disagreement(status, code);

            service.recordCashMovement(petty("40.00", List.of(tax("GST_HST", "4.60")), null));

            assertThat(recorded.getFirst().getTaxPlausibility()).isEqualTo("RATE_UNAVAILABLE");
            assertThat(recorded.getFirst().getSupplierRegistrationRequired()).isNull();
        }

        @Test
        @DisplayName("AC 17: unreachable, a timeout or a 5xx with a number → 503; without one → RATE_UNAVAILABLE")
        void unavailable() {
            taxPort.answer = new TaxPlausibilityPort.Unavailable();

            assertThatThrownBy(
                            () -> service.recordCashMovement(petty("40.00", List.of(tax("GST_HST", "4.60")), NUMBER)))
                    .isInstanceOf(TaxCheckUnavailableException.class);
            assertThat(meterRegistry.find(DrawerStatedTax.DISAGREEMENT_COUNTER).counters())
                    .isEmpty();

            service.recordCashMovement(petty("40.00", List.of(tax("GST_HST", "4.60")), null));
            assertThat(recorded.getFirst().getTaxPlausibility()).isEqualTo("RATE_UNAVAILABLE");
            assertThat(recorded.getFirst().getSupplierRegistrationRequired()).isNull();
        }

        @Test
        @DisplayName("AC 17: the WARN log carries pos-tax's actual unknown code, never the number")
        void warnCarriesTheActualCode() {
            taxPort.answer = new TaxPlausibilityPort.Disagreement(422, "SOMETHING_NEW");
            ListAppender<ILoggingEvent> logs = capture();
            try {
                assertThatThrownBy(() ->
                                service.recordCashMovement(petty("40.00", List.of(tax("GST_HST", "4.60")), NUMBER)))
                        .isInstanceOf(TaxCheckUnavailableException.class);
            } finally {
                release(logs);
            }
            assertThat(logs.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains("SOMETHING_NEW");
                    })
                    .noneSatisfy(
                            event -> assertThat(event.getFormattedMessage()).contains(NUMBER));
        }
    }

    // ── AC 21: the call order ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 21: the binding call order")
    class CallOrder {

        @Test
        @DisplayName("[M] a 503 leaves the token unused, and the corrected resend with the same requestId uses it")
        void refusalLeavesTheTokenUnspent() {
            managerApproves();
            UUID requestId = UUIDv7Generator.generate();
            taxPort.answer = new TaxPlausibilityPort.Unavailable();

            assertThatThrownBy(() -> service.recordCashMovement(
                            petty(requestId, "40.00", "token-1", SUPPLIER, List.of(tax("GST_HST", "4.60")), NUMBER)))
                    .isInstanceOf(TaxCheckUnavailableException.class);
            verify(approvalService, never()).use(any(), any(), any(), any(), any(), any(), any());

            // "Record without the number", same requestId.
            CashMovementResult result = service.recordCashMovement(
                    petty(requestId, "40.00", "token-1", SUPPLIER, List.of(tax("GST_HST", "4.60")), null));

            assertThat(result.replayed()).isFalse();
            assertThat(result.movement().approvedBy()).isEqualTo(MANAGER_ID);
            verify(approvalService).use(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("the plausibility call is made before the row lock is taken")
        void callIsOutsideTheLock() {
            List<String> order = new ArrayList<>();
            taxPort.answer = new TaxPlausibilityPort.Checked("PLAUSIBLE", false, null);
            when(sessions.findByIdForUpdate(SESSION_ID)).thenAnswer(_ -> {
                order.add(taxPort.calls.isEmpty() ? "lock-before-call" : "lock-after-call");
                return Optional.of(session);
            });

            service.recordCashMovement(petty("40.00", List.of(tax("GST_HST", "4.60")), null));

            assertThat(order).containsExactly("lock-after-call");
        }

        @Test
        @DisplayName("a replay never calls pos-tax, and a movement without stated tax never calls it")
        void replayAndNoTaxNeverCall() {
            UUID requestId = UUIDv7Generator.generate();
            CashMovementCommand command =
                    petty(requestId, "40.00", null, SUPPLIER, List.of(tax("GST_HST", "4.60")), NUMBER);
            service.recordCashMovement(command);
            assertThat(taxPort.calls).hasSize(1);

            CashMovementResult replay = service.recordCashMovement(command);
            service.recordCashMovement(petty("12.00", null, null));

            assertThat(replay.replayed()).isTrue();
            assertThat(replay.movement().supplierRegistrationNumberProvided()).isTrue();
            assertThat(taxPort.calls).hasSize(1);
        }

        @Test
        @DisplayName("a CLOSING session still refuses recording with 409, before pos-tax is asked")
        void closingSessionRefuses() {
            session.setStatus(RegisterSessionStatus.CLOSING);

            assertThatThrownBy(() -> service.recordCashMovement(petty("40.00", List.of(tax("GST_HST", "4.60")), null)))
                    .isInstanceOf(com.positivity.order.internal.exception.RegisterSessionConflictException.class);
            assertThat(taxPort.calls).isEmpty();
        }
    }

    // ── AC 24: replay and redaction ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC 24: replay and redaction")
    class ReplayAndRedaction {

        static Stream<Arguments> changes() {
            return Stream.of(
                    Arguments.of("statedTaxes amount", SUPPLIER, List.of(tax("GST_HST", "4.61")), NUMBER),
                    Arguments.of(
                            "statedTaxes regime added",
                            SUPPLIER,
                            List.of(tax("GST_HST", "4.60"), tax("QST", "1.00")),
                            NUMBER),
                    Arguments.of("supplierName", "Other Supplier", List.of(tax("GST_HST", "4.60")), NUMBER),
                    Arguments.of("number", SUPPLIER, List.of(tax("GST_HST", "4.60")), "111111111RT0001"));
        }

        @ParameterizedTest(name = "changed {0}")
        @MethodSource("changes")
        @DisplayName("[M] a changed payload under the same requestId → 409, with no value in the message")
        void changedPayloadConflicts(
                String label, String supplierName, List<CashMovementCommand.StatedTax> taxes, String number) {
            UUID requestId = UUIDv7Generator.generate();
            service.recordCashMovement(
                    petty(requestId, "40.00", null, SUPPLIER, List.of(tax("GST_HST", "4.60")), NUMBER));

            assertThatThrownBy(() ->
                            service.recordCashMovement(petty(requestId, "40.00", null, supplierName, taxes, number)))
                    .isInstanceOf(CashMovementIdempotencyConflictException.class)
                    .satisfies(e -> assertThat(e.getMessage())
                            .doesNotContain(NUMBER)
                            .doesNotContain("111111111RT0001")
                            .doesNotContain(SUPPLIER)
                            .doesNotContain("Other Supplier"));
        }

        @Test
        @DisplayName("the same stated taxes in another order replay as the same payload")
        void statedTaxesCompareAsASet() {
            UUID requestId = UUIDv7Generator.generate();
            service.recordCashMovement(petty(
                    requestId, "40.00", null, SUPPLIER, List.of(tax("GST_HST", "2.00"), tax("QST", "3.99")), null));

            CashMovementResult replay = service.recordCashMovement(petty(
                    requestId, "40.00", null, SUPPLIER, List.of(tax("QST", "3.990"), tax("GST_HST", "2.0")), null));

            assertThat(replay.replayed()).isTrue();
        }

        @Test
        @DisplayName("toString of the request, command, ValidMovement, entity and fact Movement redacts both values")
        void toStringRedacts() throws Exception {
            CashMovementRequest request = new CashMovementRequest();
            request.setSupplierName(SUPPLIER);
            request.setSupplierRegistrationNumber(NUMBER);
            CashMovementCommand command = petty("40.00", List.of(tax("GST_HST", "4.60")), NUMBER);
            CashMovement entity = CashMovement.builder()
                    .supplierName(SUPPLIER)
                    .supplierRegistrationNumber(NUMBER)
                    .build();
            Object validMovement = validMovement();
            RegisterSessionClosedV1.Movement fact = new RegisterSessionClosedV1.Movement(
                    UUIDv7Generator.generate(),
                    "PETTY_EXPENSE",
                    "OUT",
                    new BigDecimal("40.00"),
                    "CAD",
                    "SHOP_SUPPLIES",
                    null,
                    null,
                    "R-1",
                    "cashier",
                    null,
                    null,
                    Instant.now(CLOCK),
                    SUPPLIER,
                    List.of(),
                    NUMBER,
                    "PLAUSIBLE",
                    true);

            assertThat(Stream.of(request, command, entity, validMovement, fact).map(Object::toString))
                    .allSatisfy(
                            text -> assertThat(text).doesNotContain(SUPPLIER).doesNotContain(NUMBER));
        }

        /** The service's private ValidMovement, built reflectively with both values set. */
        private Object validMovement() throws Exception {
            Class<?> type = Arrays.stream(RegisterSessionServiceImpl.class.getDeclaredClasses())
                    .filter(c -> c.getSimpleName().equals("ValidMovement"))
                    .findFirst()
                    .orElseThrow();
            Constructor<?> constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return constructor.newInstance(
                    SESSION_ID,
                    UUIDv7Generator.generate(),
                    CashMovementReason.PETTY_EXPENSE,
                    new BigDecimal("40.00"),
                    "CAD",
                    "SHOP_SUPPLIES",
                    null,
                    null,
                    "R-1",
                    "note",
                    null,
                    SUPPLIER,
                    List.of(),
                    NUMBER);
        }

        @Test
        @DisplayName("responses carry supplierRegistrationNumberProvided, never the number")
        void responsesNeverCarryTheNumber() {
            service.recordCashMovement(petty("40.00", List.of(tax("GST_HST", "4.60")), NUMBER));

            assertThat(service.listCashMovements(SESSION_ID)).singleElement().satisfies(summary -> {
                assertThat(summary.supplierRegistrationNumberProvided()).isTrue();
                assertThat(summary.supplierName()).isEqualTo(SUPPLIER);
                assertThat(Arrays.stream(summary.getClass().getRecordComponents())
                                .map(java.lang.reflect.RecordComponent::getName))
                        .doesNotContain("supplierRegistrationNumber");
            });
            assertThat(service.xReport(SESSION_ID).movements())
                    .singleElement()
                    .satisfies(summary -> assertThat(summary.supplierRegistrationNumberProvided())
                            .isTrue());
        }
    }

    // ── AC 16, 17: no echo through the handler ───────────────────────────────────────────────

    @Nested
    @DisplayName("AC 16, 17: neither the body nor DEBUG logs over pos-order carry the number")
    class NoEcho {

        @ParameterizedTest(name = "number {0}")
        @ValueSource(strings = {"000000000", "00000000RT0001"})
        @DisplayName("[M] AC 16: a malformed number → 400; the body and the WARN path never carry it")
        void malformedNumberNeverEchoed(String number) {
            managerApproves();
            taxPort.answer = new TaxPlausibilityPort.Checked("PLAUSIBLE", true, Boolean.FALSE);
            RegisterSessionExceptionHandler handler = new RegisterSessionExceptionHandler(CLOCK);
            HttpServletRequest request = new MockHttpServletRequest();
            ListAppender<ILoggingEvent> logs = capture();
            ResponseEntity<ApiError> response;
            try {
                RegisterSessionRequestValidationException thrown = (RegisterSessionRequestValidationException)
                        org.assertj.core.api.Assertions.catchThrowable(() -> service.recordCashMovement(petty(
                                UUIDv7Generator.generate(),
                                "40.00",
                                "token-1",
                                SUPPLIER,
                                List.of(tax("GST_HST", "4.60")),
                                number)));
                response = handler.handleInvalidRequest(thrown, request);
            } finally {
                release(logs);
            }

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().code()).isEqualTo("REGISTER_SESSION_INVALID_ARGUMENT");
            assertThat(response.getBody().fieldErrors())
                    .extracting(ApiError.FieldError::field)
                    .containsExactly("supplierRegistrationNumber");
            assertThat(response.getBody().toString()).doesNotContain(number);
            assertThat(logs.list).isNotEmpty().allSatisfy(event -> assertNoValue(event, number));
            assertThat(recorded).isEmpty();
            verify(approvalService, never()).use(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("AC 17: the 503 carries Retry-After: 5, and neither its body nor the logs carry the number")
        void unavailableNeverEchoed() {
            taxPort.answer = new TaxPlausibilityPort.Disagreement(422, "TAX_REGIME_NOT_DECLARED");
            RegisterSessionExceptionHandler handler = new RegisterSessionExceptionHandler(CLOCK);
            ListAppender<ILoggingEvent> logs = capture();
            ResponseEntity<ApiError> response;
            try {
                TaxCheckUnavailableException thrown =
                        (TaxCheckUnavailableException) org.assertj.core.api.Assertions.catchThrowable(() ->
                                service.recordCashMovement(petty("40.00", List.of(tax("GST_HST", "4.60")), NUMBER)));
                response = handler.handleTaxCheckUnavailable(thrown, new MockHttpServletRequest());
            } finally {
                release(logs);
            }

            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
            assertThat(response.getBody().code()).isEqualTo("TAX_CHECK_UNAVAILABLE");
            assertThat(response.getBody().toString()).doesNotContain(NUMBER);
            assertThat(logs.list).allSatisfy(event -> assertNoValue(event, NUMBER));
        }

        @Test
        @DisplayName("AC 19: supplierName never appears in an INFO-or-higher log or a metric tag")
        void supplierNameNeverLoggedAtInfo() {
            taxPort.answer = new TaxPlausibilityPort.Disagreement(422, "SOMETHING_NEW");
            ListAppender<ILoggingEvent> logs = capture();
            try {
                service.recordCashMovement(petty("40.00", List.of(tax("GST_HST", "4.60")), null));
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> service.recordCashMovement(petty("40.00", List.of(tax("QST", "40.00")), null)));
            } finally {
                release(logs);
            }

            assertThat(logs.list)
                    .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                    .allSatisfy(event -> assertNoValue(event, SUPPLIER));
            assertThat(meterRegistry.getMeters())
                    .allSatisfy(meter -> assertThat(meter.getId().getTags())
                            .noneSatisfy(tag -> assertThat(tag.getValue()).contains(SUPPLIER)));
        }

        private static void assertNoValue(ILoggingEvent event, String value) {
            assertThat(event.getFormattedMessage()).doesNotContain(value);
            Throwable thrown = event.getThrowableProxy() == null
                    ? null
                    : new RuntimeException(event.getThrowableProxy().getMessage());
            if (thrown != null && thrown.getMessage() != null) {
                assertThat(thrown.getMessage()).doesNotContain(value);
            }
        }
    }

    // ── AC 19 (pos-order half): the close fact ───────────────────────────────────────────────

    @Test
    @DisplayName("AC 19: the close fact carries the five fields; statedTaxes is [] for a movement without tax")
    void closeFactCarriesTheFields() {
        service.recordCashMovement(petty("40.00", List.of(tax("GST_HST", "4.60")), NUMBER));
        service.recordCashMovement(new CashMovementCommand(
                SESSION_ID,
                UUIDv7Generator.generate(),
                "BANK_DROP",
                new BigDecimal("50.00"),
                "CAD",
                null,
                null,
                "BAG-1",
                null,
                null,
                null,
                null,
                null,
                null));
        session.setStatus(RegisterSessionStatus.CLOSING);
        session.setCountedCash(new BigDecimal("110.00"));
        org.mockito.ArgumentCaptor<RegisterSessionClosedV1> fact =
                org.mockito.ArgumentCaptor.forClass(RegisterSessionClosedV1.class);

        service.confirmClose(SESSION_ID);

        verify(publisher).publishRegisterSessionClosed(any(), fact.capture());
        assertThat(fact.getValue().movements()).hasSize(2);
        RegisterSessionClosedV1.Movement petty = fact.getValue().movements().getFirst();
        assertThat(petty.supplierName()).isEqualTo(SUPPLIER);
        assertThat(petty.statedTaxes())
                .containsExactly(new RegisterSessionClosedV1.StatedTax("GST_HST", new BigDecimal("4.60")));
        assertThat(petty.supplierRegistrationNumber()).isEqualTo(NUMBER);
        assertThat(petty.taxPlausibility()).isEqualTo("PLAUSIBLE");
        assertThat(petty.supplierRegistrationRequired()).isTrue();
        RegisterSessionClosedV1.Movement drop = fact.getValue().movements().get(1);
        assertThat(drop.statedTaxes()).isNotNull().isEmpty();
        assertThat(drop.taxPlausibility()).isNull();
        assertThat(drop.supplierRegistrationRequired()).isNull();
    }

    // ── options: evidence rule ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("options: evidenceRule from pos-tax at the currency exponent; null with pos-tax down, still answered")
    void evidenceRuleNeverFailsTheOptions() {
        taxPort.evidence = Optional.of(new TaxPlausibilityPort.EvidenceThreshold(new BigDecimal("100.0"), "CAD"));
        assertThat(service.cashMovementOptions(SESSION_ID).evidenceRule())
                .isEqualTo(new CashMovementOptions.EvidenceRule(new BigDecimal("100.00"), "CAD"));

        taxPort.evidenceFailure = new IllegalStateException("down");
        CashMovementOptions down = service.cashMovementOptions(SESSION_ID);
        assertThat(down.evidenceRule()).isNull();
        assertThat(down.categories()).isNotEmpty();

        taxPort.evidenceFailure = null;
        taxPort.evidence = Optional.empty();
        assertThat(service.cashMovementOptions(SESSION_ID).evidenceRule()).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static ObjectProvider<MeterRegistry> emptyMeters() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
        return meters;
    }

    private static ListAppender<ILoggingEvent> capture() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.positivity.order");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        return appender;
    }

    private static void release(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger("com.positivity.order");
        logger.detachAppender(appender);
        logger.setLevel(null);
        appender.stop();
    }
}
