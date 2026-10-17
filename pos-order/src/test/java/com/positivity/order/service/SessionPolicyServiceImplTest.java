package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.config.FunctionalCurrency;
import com.positivity.order.internal.entity.SessionPolicy;
import com.positivity.order.internal.entity.SessionPolicyChange;
import com.positivity.order.internal.entity.SessionPolicyType;
import com.positivity.order.internal.exception.CurrencyNotSupportedException;
import com.positivity.order.internal.exception.SessionPolicyConflictException;
import com.positivity.order.internal.exception.SessionPolicyValidationException;
import com.positivity.order.internal.repository.ExtPeopleContactUserLinkRepository;
import com.positivity.order.internal.repository.SessionPolicyChangeRepository;
import com.positivity.order.internal.repository.SessionPolicyRepository;
import com.positivity.order.internal.service.model.SessionPolicyChangeView;
import com.positivity.order.internal.service.model.SessionPolicyView;
import com.positivity.order.internal.service.model.UpdateSessionPolicyCommand;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CAP:550 S16 (#2512; §4.6 "Drawer limits", AW19, ADR-0067): the defaults, a no-op PUT that writes and
 * emits nothing, one history row per changed setting, the field rules, the currency, the version read
 * and a lost race.
 */
@DisplayName("SessionPolicyServiceImpl — drawer policy (AW19)")
class SessionPolicyServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final String JUSTIFICATION = "Tighter count after the audit";

    private final SessionPolicyRepository policies = mock(SessionPolicyRepository.class);
    private final SessionPolicyChangeRepository changes = mock(SessionPolicyChangeRepository.class);
    private final SessionPolicyWriter writer = mock(SessionPolicyWriter.class);
    private final ExtPeopleContactUserLinkRepository links = mock(ExtPeopleContactUserLinkRepository.class);
    private final ActorDisplayNames actorDisplayNames = new ActorDisplayNames(links);
    private final List<SessionPolicyChange> written = new ArrayList<>();
    private final SessionPolicyServiceImpl service = new SessionPolicyServiceImpl(
            policies,
            changes,
            writer,
            new FunctionalCurrency("USD"),
            actorDisplayNames,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(policies.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(writer.write(any(), any())).thenAnswer(inv -> {
            SessionPolicy policy = inv.getArgument(0);
            policy.setVersion(policy.getVersion() == null ? 0L : policy.getVersion() + 1);
            for (SessionPolicyChange change : (List<SessionPolicyChange>) inv.getArgument(1)) {
                change.setPolicyVersion(policy.getVersion());
                written.add(change);
            }
            return policy;
        });
        var token = new UsernamePasswordAuthenticationToken("controller-1", "n/a", List.of());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "controller-1"));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static UpdateSessionPolicyCommand put(
            Long version,
            boolean petty,
            String pettyLimit,
            boolean cod,
            String codLimit,
            String tolerance,
            String justification) {
        return put(version, "USD", petty, pettyLimit, cod, codLimit, tolerance, justification);
    }

    private static UpdateSessionPolicyCommand put(
            Long version,
            String currency,
            boolean petty,
            String pettyLimit,
            boolean cod,
            String codLimit,
            String tolerance,
            String justification) {
        return new UpdateSessionPolicyCommand(
                version,
                currency,
                petty,
                pettyLimit == null ? null : new BigDecimal(pettyLimit),
                cod,
                codLimit == null ? null : new BigDecimal(codLimit),
                tolerance == null ? null : new BigDecimal(tolerance),
                justification);
    }

    private static SessionPolicy stored(long version) {
        return SessionPolicy.builder()
                .version(version)
                .pettyExpenseAllowed(true)
                .pettyExpenseLimit(new BigDecimal("50.0000"))
                .vendorCodAllowed(false)
                .overShortTolerance(new BigDecimal("5.0000"))
                .currencyCode("USD")
                .updatedBy("someone")
                .build();
    }

    @Test
    @DisplayName("AC5: a fresh tenant reads petty on at 50.00, COD off, bank drop and float fixed, tolerance 5.00 USD")
    void freshTenantDefaults() {
        SessionPolicyView policy = service.current();

        assertThat(policy.version()).isNull();
        assertThat(policy.currencyCode()).isEqualTo("USD");
        assertThat(policy.allowed(SessionPolicyType.PETTY_EXPENSE)).isTrue();
        assertThat(policy.cashierLimit(SessionPolicyType.PETTY_EXPENSE)).isEqualByComparingTo("50.00");
        assertThat(policy.allowed(SessionPolicyType.VENDOR_COD)).isFalse();
        assertThat(policy.allowed(SessionPolicyType.BANK_DROP)).isTrue();
        assertThat(policy.cashierLimit(SessionPolicyType.BANK_DROP)).isNull();
        assertThat(policy.allowed(SessionPolicyType.FLOAT_CHANGE)).isTrue();
        assertThat(policy.alwaysNeedsManager(SessionPolicyType.FLOAT_CHANGE)).isTrue();
        assertThat(policy.rows())
                .extracting(SessionPolicyView.TypeRow::editable)
                .containsExactly(true, true, false, false);
        assertThat(policy.overShortTolerance()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("AC6: changing the tolerance 5.00 → 3.00 writes one history row with actor and justification")
    void oneHistoryRowPerChangedSetting() {
        SessionPolicyView updated = service.update(put(null, true, "50.00", false, null, "3.00", JUSTIFICATION));

        assertThat(updated.overShortTolerance()).isEqualByComparingTo("3.00");
        assertThat(updated.currencyCode()).isEqualTo("USD");
        assertThat(written).singleElement().satisfies(change -> {
            assertThat(change.getSetting()).isEqualTo("OVER_SHORT_TOLERANCE");
            assertThat(change.getOldValue()).isEqualTo("5.00");
            assertThat(change.getNewValue()).isEqualTo("3.00");
            assertThat(change.getActor()).isEqualTo("controller-1");
            assertThat(change.getJustification()).isEqualTo(JUSTIFICATION);
            assertThat(change.getChangedAt()).isEqualTo(NOW);
            assertThat(change.getPolicyVersion()).isZero();
        });
    }

    @Test
    @DisplayName("l6: a PUT that changes nothing writes nothing and emits nothing (the writer emits the event)")
    void noOpPutWritesNothing() {
        SessionPolicyView unchanged = service.update(put(null, true, "50.00", false, null, "5.00", JUSTIFICATION));

        assertThat(unchanged.version()).isNull();
        verify(writer, never()).write(any(), any());
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("M4: a PUT based on a stale version, or with none while a policy is stored, is 409")
    void staleVersionConflicts() {
        when(policies.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored(4L)));

        assertThatThrownBy(() -> service.update(put(3L, true, "50.00", false, null, "3.00", JUSTIFICATION)))
                .isInstanceOf(SessionPolicyConflictException.class);
        assertThatThrownBy(() -> service.update(put(null, true, "50.00", false, null, "3.00", JUSTIFICATION)))
                .isInstanceOf(SessionPolicyConflictException.class);
        verify(writer, never()).write(any(), any());

        SessionPolicyView current = service.update(put(4L, true, "50.00", false, null, "3.00", JUSTIFICATION));
        assertThat(current.version()).isEqualTo(5L);
    }

    @Test
    @DisplayName("M4: a version named while no policy is stored is 409 too")
    void versionWithoutStoredPolicyConflicts() {
        assertThatThrownBy(() -> service.update(put(0L, true, "50.00", false, null, "3.00", JUSTIFICATION)))
                .isInstanceOf(SessionPolicyConflictException.class);
    }

    @Test
    @DisplayName("ADR-0067: a missing or non-ISO currency is 400, another ISO currency 422")
    void currencyRules() {
        assertThatThrownBy(() -> service.update(put(null, null, true, "50.00", false, null, "3.00", JUSTIFICATION)))
                .isInstanceOfSatisfying(
                        SessionPolicyValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("currencyCode"));
        assertThatThrownBy(() -> service.update(put(null, "U$D", true, "50.00", false, null, "3.00", JUSTIFICATION)))
                .isInstanceOfSatisfying(
                        SessionPolicyValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("currencyCode"));
        assertThatThrownBy(() -> service.update(put(null, "CAD", true, "50.00", false, null, "3.00", JUSTIFICATION)))
                .isInstanceOf(CurrencyNotSupportedException.class);
        verify(writer, never()).write(any(), any());
    }

    @Test
    @DisplayName("AC6: a 9-character justification is a 400")
    void shortJustification() {
        assertThatThrownBy(() -> service.update(put(null, true, "50.00", false, null, "3.00", "123456789")))
                .isInstanceOfSatisfying(SessionPolicyValidationException.class, e -> {
                    assertThat(e.getField()).isEqualTo("justification");
                    assertThat(e.getMessage()).contains("justification");
                });
        verify(writer, never()).write(any(), any());
    }

    @Test
    @DisplayName("a negative limit or tolerance, an allowed type without a limit, or COD on is a 400")
    void fieldRules() {
        java.util.Map<UpdateSessionPolicyCommand, String> badByField = new java.util.LinkedHashMap<>();
        badByField.put(put(null, true, "-1.00", false, null, "5.00", JUSTIFICATION), "pettyExpense.cashierLimit");
        badByField.put(put(null, true, "50.00", false, null, "-0.01", JUSTIFICATION), "overShortTolerance");
        badByField.put(put(null, true, null, false, null, "5.00", JUSTIFICATION), "pettyExpense.cashierLimit");
        badByField.put(put(null, true, "50.00", true, null, "5.00", JUSTIFICATION), "vendorCod.cashierLimit");
        badByField.put(put(null, true, "50.00", true, "100.00", "5.00", JUSTIFICATION), "vendorCod.allowed");
        badByField.put(put(null, true, "50.00", false, null, null, JUSTIFICATION), "overShortTolerance");
        badByField.forEach((bad, field) -> assertThatThrownBy(() -> service.update(bad))
                .as(field)
                .isInstanceOfSatisfying(
                        SessionPolicyValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo(field)));
        // #2674: the missing-allowed refusals name the one that is missing.
        assertThatThrownBy(() -> service.update(new UpdateSessionPolicyCommand(
                        null,
                        "USD",
                        null,
                        new BigDecimal("50.00"),
                        false,
                        null,
                        new BigDecimal("5.00"),
                        JUSTIFICATION)))
                .isInstanceOfSatisfying(
                        SessionPolicyValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("pettyExpense.allowed"));
        assertThatThrownBy(() -> service.update(new UpdateSessionPolicyCommand(
                        null, "USD", true, new BigDecimal("50.00"), null, null, new BigDecimal("5.00"), JUSTIFICATION)))
                .isInstanceOfSatisfying(
                        SessionPolicyValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("vendorCod.allowed"));
        verify(writer, never()).write(any(), any());
    }

    @Test
    @DisplayName("switching petty off and changing its limit writes a row per setting")
    void severalSettingsSeveralRows() {
        service.update(put(null, false, "40.00", false, null, "5.00", JUSTIFICATION));

        assertThat(written)
                .extracting(SessionPolicyChange::getSetting)
                .containsExactly("PETTY_EXPENSE_ALLOWED", "PETTY_EXPENSE_LIMIT");
        assertThat(written.get(0).getOldValue()).isEqualTo("true");
        assertThat(written.get(0).getNewValue()).isEqualTo("false");
    }

    @Test
    @DisplayName("a PUT that lost a race to another PUT is a 409 conflict")
    void lostRaceConflicts() {
        doThrow(new ObjectOptimisticLockingFailureException(SessionPolicy.class, "x"))
                .when(writer)
                .write(any(), any());

        assertThatThrownBy(() -> service.update(put(null, true, "50.00", false, null, "3.00", JUSTIFICATION)))
                .isInstanceOf(SessionPolicyConflictException.class);
        assertThat(written).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // #2608: history[].actorName, resolved at read time from the people-contact copy
    // ---------------------------------------------------------------------------------------

    private static SessionPolicyChange change(String actor, Instant at) {
        return SessionPolicyChange.builder()
                .setting("OVER_SHORT_TOLERANCE")
                .oldValue("5.00")
                .newValue("3.00")
                .actor(actor)
                .justification(JUSTIFICATION)
                .policyVersion(1L)
                .changedAt(at)
                .build();
    }

    private static ExtPeopleContactUserLinkRepository.LinkedName linked(String username, String first, String last) {
        return new ExtPeopleContactUserLinkRepository.LinkedName() {
            @Override
            public String getUsername() {
                return username;
            }

            @Override
            public String getFirstName() {
                return first;
            }

            @Override
            public String getLastName() {
                return last;
            }
        };
    }

    @Test
    @DisplayName("#2608 test 1: an actor with an ACTIVE link to a copied person is served \"First Last\"")
    void historyServesTheActorsName() {
        when(changes.findAllByOrderByChangedAtDescChangeIdDesc()).thenReturn(List.of(change("dana.reyes", NOW)));
        when(links.findActiveNames(any())).thenReturn(List.of(linked("dana.reyes", "Dana", " Reyes ")));

        List<SessionPolicyChangeView> history = service.history();

        assertThat(history).singleElement().satisfies(row -> {
            assertThat(row.actorName()).isEqualTo("Dana Reyes");
            assertThat(row.actor()).isEqualTo("dana.reyes");
        });
    }

    @Test
    @DisplayName(
            "#2608 tests 2-3: no link, an unresolved person, blank names and system serve null, never the username")
    void unresolvedActorsServeNull() {
        // findActiveNames answers only ACTIVE links to a copied, not deleted person (its query); an actor it does not
        // answer for, or one whose person has blank names, has no name. system is never looked up.
        when(changes.findAllByOrderByChangedAtDescChangeIdDesc())
                .thenReturn(List.of(
                        change("no.link", NOW),
                        change("removed.link", NOW.minusSeconds(1)),
                        change("blank.names", NOW.minusSeconds(2)),
                        change("system", NOW.minusSeconds(3))));
        when(links.findActiveNames(any())).thenReturn(List.of(linked("blank.names", " ", null)));

        List<SessionPolicyChangeView> history = service.history();

        assertThat(history).extracting(SessionPolicyChangeView::actorName).containsOnlyNulls();
        assertThat(history)
                .extracting(SessionPolicyChangeView::actor)
                .containsExactly("no.link", "removed.link", "blank.names", "system");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> asked = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(links).findActiveNames(asked.capture());
        assertThat(asked.getValue()).doesNotContain("system");
    }

    @Test
    @DisplayName(
            "#2608 test 4: several rows by repeated actors resolve in exactly one lookup of the distinct usernames")
    void historyResolvesInOneLookup() {
        when(changes.findAllByOrderByChangedAtDescChangeIdDesc())
                .thenReturn(List.of(
                        change("dana.reyes", NOW),
                        change("sam.ortiz", NOW.minusSeconds(1)),
                        change("dana.reyes", NOW.minusSeconds(2)),
                        change("sam.ortiz", NOW.minusSeconds(3))));
        when(links.findActiveNames(any()))
                .thenReturn(List.of(linked("dana.reyes", "Dana", "Reyes"), linked("sam.ortiz", "Sam", "Ortiz")));

        List<SessionPolicyChangeView> history = service.history();

        assertThat(history)
                .extracting(SessionPolicyChangeView::actorName)
                .containsExactly("Dana Reyes", "Sam Ortiz", "Dana Reyes", "Sam Ortiz");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> asked = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(links, org.mockito.Mockito.times(1)).findActiveNames(asked.capture());
        assertThat(asked.getValue()).containsExactlyInAnyOrder("dana.reyes", "sam.ortiz");
    }

    @Test
    @DisplayName("#2608 test 5: a row written before its person was copied serves the name once the copy holds it")
    void lateArrivalNamesAnOldRow() {
        SessionPolicyChange old = change("dana.reyes", NOW);
        when(changes.findAllByOrderByChangedAtDescChangeIdDesc()).thenReturn(List.of(old));
        when(links.findActiveNames(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(linked("dana.reyes", "Dana", "Reyes")));

        assertThat(service.history())
                .singleElement()
                .extracting(SessionPolicyChangeView::actorName)
                .isNull();
        assertThat(service.history())
                .singleElement()
                .extracting(SessionPolicyChangeView::actorName)
                .isEqualTo("Dana Reyes");
        // Resolved at read time: the immutable row was never written.
        verify(changes, never()).save(any());
        assertThat(old.getActor()).isEqualTo("dana.reyes");
    }
}
