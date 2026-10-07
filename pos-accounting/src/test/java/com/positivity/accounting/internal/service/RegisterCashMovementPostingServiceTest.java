package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.domainevents.order.RegisterSessionClosedV1.Movement;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link RegisterCashMovementPostingService} (CAP:550 S17, #2513): the legs per reason and their
 * mapping keys, business-time dating, the lines' dimensions, idempotency per movement, the currency hold, and the
 * vendor cash on delivery movement held until its posting exists.
 */
class RegisterCashMovementPostingServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Instant CLOSED_AT = Instant.parse("2026-10-06T22:15:00Z");
    private static final LocalDateTime POSTING_DATE = LocalDateTime.ofInstant(CLOSED_AT, ZoneOffset.UTC);
    private static final String ENVELOPE_EVENT_ID = "01960003-0000-7000-8000-0000000000e2";

    private static final UUID SHOP_SUPPLIES_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000006340");
    private static final UUID STAFF_MEALS_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000006295");
    private static final UUID CLEARING_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000001095");

    private static final UUID PETTY_1 = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID PETTY_2 = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID COD = UUID.fromString("00000000-0000-0000-0000-0000000000f3");
    private static final UUID DROP = UUID.fromString("00000000-0000-0000-0000-0000000000f4");

    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final GLMappingResolver glMappingResolver = mock(GLMappingResolver.class);
    private final GLPostingService glPostingService = mock(GLPostingService.class);
    private final KafkaFactIngestionRecorder ingestionRecorder = mock(KafkaFactIngestionRecorder.class);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private RegisterCashMovementPostingService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<MeterRegistry> registry = mock(ObjectProvider.class);
        when(registry.getIfAvailable()).thenReturn(meterRegistry);
        service = new RegisterCashMovementPostingService(
                TestZoneResolvers.utc(TEST_CLOCK),
                idempotencyService,
                glMappingResolver,
                glPostingService,
                new LedgerCurrency("USD"),
                ingestionRecorder,
                registry);
        when(glMappingResolver.resolveGLAccount("REGISTER_CASH_MOVEMENT", "PETTY_EXPENSE_SHOP_SUPPLIES", POSTING_DATE))
                .thenReturn(SHOP_SUPPLIES_ACCOUNT);
        when(glMappingResolver.resolveGLAccount("REGISTER_CASH_MOVEMENT", "PETTY_EXPENSE_STAFF_MEALS", POSTING_DATE))
                .thenReturn(STAFF_MEALS_ACCOUNT);
        when(glMappingResolver.resolveGLAccount("REGISTER_CASH_MOVEMENT", "CASH_CLEARING", POSTING_DATE))
                .thenReturn(CLEARING_ACCOUNT);
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    private static Movement petty(UUID movementId, String category, String amount, String receipt) {
        return movement(movementId, "PETTY_EXPENSE", "OUT", amount, "USD", category, null, null, receipt);
    }

    private static Movement movement(
            UUID movementId,
            String reason,
            String direction,
            String amount,
            String currencyCode,
            String category,
            UUID vendorId,
            String bagNumber,
            String receipt) {
        return new Movement(
                movementId,
                reason,
                direction,
                new BigDecimal(amount),
                currencyCode,
                category,
                vendorId,
                bagNumber,
                receipt,
                "clerk-1",
                null,
                null,
                CLOSED_AT.minusSeconds(3600));
    }

    private static RegisterSessionClosedV1 fact(String currencyCode, UUID locationId, List<Movement> movements) {
        return new RegisterSessionClosedV1(
                SESSION_ID,
                "terminal-1",
                locationId,
                "clerk-1",
                "clerk-2",
                new BigDecimal("200.00"),
                new BigDecimal("160.00"),
                new BigDecimal("160.00"),
                BigDecimal.ZERO,
                false,
                currencyCode,
                List.of(),
                new BigDecimal("-40.40"),
                CLOSED_AT.minusSeconds(28_800),
                CLOSED_AT,
                movements);
    }

    private static RegisterSessionClosedV1 fact(Movement... movements) {
        return fact("USD", LOCATION_ID, Arrays.asList(movements));
    }

    private void postsReturn(UUID... entries) {
        var stub = when(glPostingService.postRegisterCashMovement(
                any(), any(), any(), any(), any(), anyString(), anyString(), any()));
        for (UUID entry : entries) {
            stub = stub.thenReturn(entry);
        }
    }

    private double counted(String metric, String reason) {
        var counter = meterRegistry.find(metric).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    // ---- petty expenses ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "AC1: each petty expense posts Dr its category's account / Cr cash clearing for its gross amount, dated"
                    + " closedAt, both lines dimensioned by register, session and the session's location")
    void pettyExpensesPostPerCategory() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        postsReturn(first, second);

        FactPostingOutcome outcome = service.postMovements(
                fact(petty(PETTY_1, "SHOP_SUPPLIES", "18.40", "R-1042"), petty(PETTY_2, "STAFF_MEALS", "22.00", null)),
                ENVELOPE_EVENT_ID);

        assertThat(outcome).isEqualTo(FactPostingOutcome.posted(first));
        Map<String, String> dimensions = Map.of(
                "registerId", "terminal-1", "sessionId", SESSION_ID.toString(), "locationId", LOCATION_ID.toString());
        ArgumentCaptor<String> descriptions = ArgumentCaptor.forClass(String.class);
        verify(glPostingService)
                .postRegisterCashMovement(
                        eq(RegisterCashMovementPostingService.toSourceEventId(PETTY_1)),
                        eq(SHOP_SUPPLIES_ACCOUNT),
                        eq(CLEARING_ACCOUNT),
                        eq(new BigDecimal("18.40")),
                        eq(POSTING_DATE),
                        descriptions.capture(),
                        anyString(),
                        eq(dimensions));
        verify(glPostingService)
                .postRegisterCashMovement(
                        eq(RegisterCashMovementPostingService.toSourceEventId(PETTY_2)),
                        eq(STAFF_MEALS_ACCOUNT),
                        eq(CLEARING_ACCOUNT),
                        eq(new BigDecimal("22.00")),
                        eq(POSTING_DATE),
                        descriptions.capture(),
                        anyString(),
                        eq(dimensions));
        assertThat(descriptions.getAllValues().getFirst())
                .contains("petty expense", "SHOP_SUPPLIES", "18.40", "receipt R-1042", "register terminal-1")
                .contains("session closed " + CLOSED_AT)
                .doesNotContain(SESSION_ID.toString());
        assertThat(descriptions.getAllValues().get(1)).contains("STAFF_MEALS").doesNotContain("receipt");
        verify(idempotencyService).registerKey("REGISTER_CASH_MOVEMENT_GL_POSTING:" + PETTY_1, first);
        verify(idempotencyService).registerKey("REGISTER_CASH_MOVEMENT_GL_POSTING:" + PETTY_2, second);
        assertThat(counted("accounting.cash_movement.posted", "PETTY_EXPENSE")).isEqualTo(2);
        verifyNoInteractions(ingestionRecorder);
    }

    @Test
    @DisplayName("The source event id is nameUUIDFromBytes(\"REGISTER_CASH_MOVEMENT:\" + movementId)")
    void sourceEventIdIsDeterministic() {
        assertThat(RegisterCashMovementPostingService.toSourceEventId(PETTY_1))
                .isEqualTo(UUID.nameUUIDFromBytes(
                        ("REGISTER_CASH_MOVEMENT:" + PETTY_1).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("A session without a location posts lines dimensioned by register and session only")
    void noLocationNoLocationDimension() {
        postsReturn(UUID.randomUUID());

        service.postMovements(
                fact("USD", null, List.of(petty(PETTY_1, "SHOP_SUPPLIES", "5.00", null))), ENVELOPE_EVENT_ID);

        verify(glPostingService)
                .postRegisterCashMovement(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        eq(Map.of("registerId", "terminal-1", "sessionId", SESSION_ID.toString())));
    }

    // ---- idempotency ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC3: a redelivered fact posts nothing again: every movement key is registered, DUPLICATE_IGNORED")
    void redeliveryPostsNothing() {
        when(idempotencyService.isKeyProcessed(anyString())).thenReturn(true);

        FactPostingOutcome outcome = service.postMovements(
                fact(petty(PETTY_1, "SHOP_SUPPLIES", "18.40", null), petty(PETTY_2, "STAFF_MEALS", "22.00", null)),
                ENVELOPE_EVENT_ID);

        assertThat(outcome)
                .isEqualTo(new FactPostingOutcome.AlreadyPosted(
                        null, RegisterCashMovementPostingService.toSourceEventId(PETTY_1)));
        verifyNoInteractions(glPostingService);
        verify(idempotencyService, never()).registerKey(anyString(), any());
    }

    @Test
    @DisplayName("Idempotency is per movement: a movement not yet posted posts while the posted one is skipped")
    void onlyTheUnpostedMovementPosts() {
        UUID entry = UUID.randomUUID();
        postsReturn(entry);
        when(idempotencyService.isKeyProcessed("REGISTER_CASH_MOVEMENT_GL_POSTING:" + PETTY_1))
                .thenReturn(true);

        FactPostingOutcome outcome = service.postMovements(
                fact(petty(PETTY_1, "SHOP_SUPPLIES", "18.40", null), petty(PETTY_2, "STAFF_MEALS", "22.00", null)),
                ENVELOPE_EVENT_ID);

        assertThat(outcome).isEqualTo(FactPostingOutcome.posted(entry));
        verify(glPostingService, times(1))
                .postRegisterCashMovement(any(), any(), any(), any(), any(), anyString(), anyString(), any());
        verify(idempotencyService).registerKey("REGISTER_CASH_MOVEMENT_GL_POSTING:" + PETTY_2, entry);
    }

    // ---- reasons that post nothing at close ----------------------------------------------------------------------

    @Test
    @DisplayName("AC4: a bank drop, a float increase and a float decrease post nothing at close")
    void dropsAndFloatChangesPostNothing() {
        FactPostingOutcome outcome = service.postMovements(
                fact(
                        movement(DROP, "BANK_DROP", "OUT", "300.00", "USD", null, null, "BAG-7", null),
                        movement(UUID.randomUUID(), "FLOAT_INCREASE", "IN", "50.00", "USD", null, null, null, null),
                        movement(UUID.randomUUID(), "FLOAT_DECREASE", "OUT", "20.00", "USD", null, null, null, null)),
                ENVELOPE_EVENT_ID);

        assertThat(outcome).isInstanceOf(FactPostingOutcome.NothingToPost.class);
        verifyNoInteractions(glPostingService, glMappingResolver, idempotencyService, ingestionRecorder);
    }

    @Test
    @DisplayName("AC8: a schema-1 fact (no movements) posts nothing here")
    void schemaOneFactPostsNothing() {
        assertThat(service.postMovements(fact("USD", LOCATION_ID, null), ENVELOPE_EVENT_ID))
                .isInstanceOf(FactPostingOutcome.NothingToPost.class);
        verifyNoInteractions(glPostingService, glMappingResolver, idempotencyService, ingestionRecorder);
    }

    @Test
    @DisplayName("Vendor cash on delivery is held, not posted, until its AP payment posting exists (#2576): no key is"
            + " registered and it is counted; the session's petty expense still posts")
    void vendorCashOnDeliveryIsHeldNotPosted() {
        UUID entry = UUID.randomUUID();
        postsReturn(entry);

        FactPostingOutcome outcome = service.postMovements(
                fact(
                        movement(COD, "VENDOR_COD", "OUT", "145.00", "USD", null, UUID.randomUUID(), null, null),
                        petty(PETTY_1, "SHOP_SUPPLIES", "40.00", null)),
                ENVELOPE_EVENT_ID);

        assertThat(outcome).isEqualTo(FactPostingOutcome.posted(entry));
        verify(glPostingService, times(1))
                .postRegisterCashMovement(any(), any(), any(), any(), any(), anyString(), anyString(), any());
        verify(glMappingResolver, never())
                .resolveGLAccount(eq("REGISTER_CASH_MOVEMENT"), eq("ACCOUNTS_PAYABLE"), any());
        verify(idempotencyService, never()).isKeyProcessed("REGISTER_CASH_MOVEMENT_GL_POSTING:" + COD);
        verify(idempotencyService, never()).registerKey(eq("REGISTER_CASH_MOVEMENT_GL_POSTING:" + COD), any());
        assertThat(counted("accounting.cash_movement.unposted", "VENDOR_COD")).isEqualTo(1);
    }

    @Test
    @DisplayName("A session of vendor cash on delivery only posts nothing here")
    void codOnlyPostsNothing() {
        assertThat(service.postMovements(
                        fact(movement(COD, "VENDOR_COD", "OUT", "145.00", "USD", null, UUID.randomUUID(), null, null)),
                        ENVELOPE_EVENT_ID))
                .isInstanceOf(FactPostingOutcome.NothingToPost.class);
        verifyNoInteractions(glPostingService, idempotencyService, ingestionRecorder);
    }

    @Test
    @DisplayName(
            "A movement recorded before the fixed reasons, or with a reason accounting does not know, posts nothing"
                    + " and is counted UNCLASSIFIED")
    void unclassifiedMovementsPostNothing() {
        assertThat(service.postMovements(
                        fact(
                                movement(UUID.randomUUID(), null, "OUT", "9.99", "USD", null, null, null, null),
                                movement(UUID.randomUUID(), "TIP_OUT", "OUT", "5.00", "USD", null, null, null, null)),
                        ENVELOPE_EVENT_ID))
                .isInstanceOf(FactPostingOutcome.NothingToPost.class);
        verifyNoInteractions(glPostingService);
        assertThat(counted("accounting.cash_movement.unposted", "UNCLASSIFIED")).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "Reason dispositions: petty posts, COD is held, drops and float changes post nothing, others unclassified")
    void dispositions() {
        assertThat(RegisterCashMovementPostingService.dispositionOf("PETTY_EXPENSE"))
                .isEqualTo(RegisterCashMovementPostingService.Disposition.POST_PETTY_EXPENSE);
        assertThat(RegisterCashMovementPostingService.dispositionOf("VENDOR_COD"))
                .isEqualTo(RegisterCashMovementPostingService.Disposition.HOLD_VENDOR_COD);
        List<RegisterCashMovementPostingService.Disposition> nothing = new ArrayList<>();
        for (String reason : List.of("BANK_DROP", "FLOAT_INCREASE", "FLOAT_DECREASE")) {
            nothing.add(RegisterCashMovementPostingService.dispositionOf(reason));
        }
        assertThat(nothing).containsOnly(RegisterCashMovementPostingService.Disposition.NOTHING_AT_CLOSE);
        assertThat(RegisterCashMovementPostingService.dispositionOf(null))
                .isEqualTo(RegisterCashMovementPostingService.Disposition.UNCLASSIFIED);
    }

    // ---- currency and failures -----------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "AC7: a session closed in a non-ledger currency posts nothing and is held once as CURRENCY_NOT_SUPPORTED")
    void foreignSessionIsHeld() {
        when(ingestionRecorder.recordCurrencyHeld(any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(true);
        RegisterSessionClosedV1 fact = fact(
                "EUR",
                LOCATION_ID,
                List.of(movement(PETTY_1, "PETTY_EXPENSE", "OUT", "18.40", "EUR", "SHOP_SUPPLIES", null, null, null)));

        assertThat(service.postMovements(fact, ENVELOPE_EVENT_ID)).isInstanceOf(FactPostingOutcome.CurrencyHeld.class);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(ingestionRecorder)
                .recordCurrencyHeld(
                        eq("pos-order"),
                        eq(RegisterSessionClosedV1.EVENT_TYPE),
                        eq(ENVELOPE_EVENT_ID),
                        eq(SESSION_ID),
                        eq(POSTING_DATE),
                        eq(fact),
                        detail.capture());
        assertThat(detail.getValue()).contains("EUR", "USD", "ADR-0067");
        verifyNoInteractions(glPostingService, glMappingResolver, idempotencyService);
    }

    @Test
    @DisplayName("ADR-0067: a movement whose own currency is not the ledger's holds the session too")
    void foreignMovementIsHeld() {
        assertThat(service.postMovements(
                        fact(movement(
                                PETTY_1, "PETTY_EXPENSE", "OUT", "18.40", "CAD", "SHOP_SUPPLIES", null, null, null)),
                        ENVELOPE_EVENT_ID))
                .isInstanceOf(FactPostingOutcome.CurrencyHeld.class);
        verify(ingestionRecorder)
                .recordCurrencyHeld(
                        any(),
                        any(),
                        any(),
                        eq(SESSION_ID),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.contains("CAD"));
        verifyNoInteractions(glPostingService);
    }

    @Test
    @DisplayName("A missing mapping propagates for retry / DLQ and registers no key")
    void missingMappingPropagates() {
        when(glMappingResolver.resolveGLAccount("REGISTER_CASH_MOVEMENT", "PETTY_EXPENSE_SMALL_TOOLS", POSTING_DATE))
                .thenThrow(new GLMappingNotConfiguredException("Mapping key not configured"));

        assertThatThrownBy(() ->
                        service.postMovements(fact(petty(PETTY_1, "SMALL_TOOLS", "18.40", null)), ENVELOPE_EVENT_ID))
                .isInstanceOf(GLMappingNotConfiguredException.class);
        verify(idempotencyService, never()).registerKey(anyString(), any());
    }

    @Test
    @DisplayName("A petty expense that breaks the close fact's contract fails the fact; nothing posts")
    void contractViolationFailsTheFact() {
        assertThatThrownBy(() -> service.postMovements(
                        fact(
                                petty(PETTY_1, "SHOP_SUPPLIES", "18.40", null),
                                movement(
                                        PETTY_2,
                                        "PETTY_EXPENSE",
                                        "IN",
                                        "5.00",
                                        "USD",
                                        "SHOP_SUPPLIES",
                                        null,
                                        null,
                                        null)),
                        ENVELOPE_EVENT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PETTY_2.toString())
                .hasMessageContaining("direction IN");
        assertThatThrownBy(() -> service.postMovements(fact(petty(PETTY_1, null, "18.40", null)), ENVELOPE_EVENT_ID))
                .hasMessageContaining("no categoryCode");
        verifyNoInteractions(glPostingService);
    }
}
