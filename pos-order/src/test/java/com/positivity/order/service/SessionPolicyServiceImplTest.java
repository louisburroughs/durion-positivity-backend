package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.entity.SessionPolicy;
import com.positivity.order.internal.entity.SessionPolicyChange;
import com.positivity.order.internal.entity.SessionPolicyType;
import com.positivity.order.internal.exception.SessionPolicyConflictException;
import com.positivity.order.internal.exception.SessionPolicyValidationException;
import com.positivity.order.internal.repository.SessionPolicyChangeRepository;
import com.positivity.order.internal.repository.SessionPolicyRepository;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CAP:550 S16 (#2512; §4.6 "Drawer limits", AW19): the defaults, a no-op PUT, one history row per
 * changed setting, the field rules and a lost race.
 */
@DisplayName("SessionPolicyServiceImpl — drawer policy (AW19)")
class SessionPolicyServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final String JUSTIFICATION = "Tighter count after the audit";

    private final SessionPolicyRepository policies = mock(SessionPolicyRepository.class);
    private final SessionPolicyChangeRepository changes = mock(SessionPolicyChangeRepository.class);
    private final List<SessionPolicyChange> written = new ArrayList<>();
    private final SessionPolicyServiceImpl service =
            new SessionPolicyServiceImpl(policies, changes, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(policies.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(policies.saveAndFlush(any())).thenAnswer(inv -> {
            SessionPolicy policy = inv.getArgument(0);
            policy.setVersion(policy.getVersion() == null ? 0L : policy.getVersion() + 1);
            return policy;
        });
        when(changes.save(any())).thenAnswer(inv -> {
            written.add(inv.getArgument(0));
            return inv.getArgument(0);
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
            boolean petty, String pettyLimit, boolean cod, String codLimit, String tolerance, String justification) {
        return new UpdateSessionPolicyCommand(
                petty,
                pettyLimit == null ? null : new BigDecimal(pettyLimit),
                cod,
                codLimit == null ? null : new BigDecimal(codLimit),
                tolerance == null ? null : new BigDecimal(tolerance),
                justification);
    }

    @Test
    @DisplayName("AC5: a fresh tenant reads petty on at 50.00, COD off, bank drop and float fixed, tolerance 5.00")
    void freshTenantDefaults() {
        SessionPolicyView policy = service.current();

        assertThat(policy.version()).isNull();
        assertThat(policy.allowed(SessionPolicyType.PETTY_EXPENSE)).isTrue();
        assertThat(policy.cashierLimit(SessionPolicyType.PETTY_EXPENSE)).isEqualByComparingTo("50.00");
        assertThat(policy.allowed(SessionPolicyType.VENDOR_COD)).isFalse();
        assertThat(policy.allowed(SessionPolicyType.BANK_DROP)).isTrue();
        assertThat(policy.cashierLimit(SessionPolicyType.BANK_DROP)).isNull();
        assertThat(policy.allowed(SessionPolicyType.FLOAT_CHANGE)).isTrue();
        assertThat(policy.alwaysNeedsManager(SessionPolicyType.FLOAT_CHANGE)).isTrue();
        assertThat(SessionPolicyType.BANK_DROP.configurable()).isFalse();
        assertThat(SessionPolicyType.FLOAT_CHANGE.configurable()).isFalse();
        assertThat(policy.overShortTolerance()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("AC6: changing the tolerance 5.00 → 3.00 writes one history row with actor and justification")
    void oneHistoryRowPerChangedSetting() {
        SessionPolicyView updated = service.update(put(true, "50.00", false, null, "3.00", JUSTIFICATION));

        assertThat(updated.overShortTolerance()).isEqualByComparingTo("3.00");
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
    @DisplayName("a PUT that changes nothing writes nothing")
    void noOpPutWritesNothing() {
        SessionPolicyView unchanged = service.update(put(true, "50.00", false, null, "5.00", JUSTIFICATION));

        assertThat(unchanged.version()).isNull();
        verify(policies, never()).saveAndFlush(any());
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("AC6: a 9-character justification is a 400")
    void shortJustification() {
        assertThatThrownBy(() -> service.update(put(true, "50.00", false, null, "3.00", "123456789")))
                .isInstanceOf(SessionPolicyValidationException.class)
                .hasMessageContaining("justification");
        verify(policies, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a negative limit or tolerance, an allowed type without a limit, or COD on is a 400")
    void fieldRules() {
        for (UpdateSessionPolicyCommand bad : List.of(
                put(true, "-1.00", false, null, "5.00", JUSTIFICATION),
                put(true, "50.00", false, null, "-0.01", JUSTIFICATION),
                put(true, null, false, null, "5.00", JUSTIFICATION),
                put(true, "50.00", true, null, "5.00", JUSTIFICATION),
                put(true, "50.00", true, "100.00", "5.00", JUSTIFICATION),
                put(true, "50.00", false, null, null, JUSTIFICATION))) {
            assertThatThrownBy(() -> service.update(bad)).isInstanceOf(SessionPolicyValidationException.class);
        }
        verify(policies, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("switching petty off and changing its limit writes a row per setting")
    void severalSettingsSeveralRows() {
        service.update(put(false, "40.00", false, null, "5.00", JUSTIFICATION));

        assertThat(written)
                .extracting(SessionPolicyChange::getSetting)
                .containsExactly("PETTY_EXPENSE_ALLOWED", "PETTY_EXPENSE_LIMIT");
        assertThat(written.get(0).getOldValue()).isEqualTo("true");
        assertThat(written.get(0).getNewValue()).isEqualTo("false");
    }

    @Test
    @DisplayName("a PUT that lost a race to another PUT is a 409 conflict")
    void lostRaceConflicts() {
        org.mockito.Mockito.doThrow(new ObjectOptimisticLockingFailureException(SessionPolicy.class, "x"))
                .when(policies)
                .saveAndFlush(any());

        assertThatThrownBy(() -> service.update(put(true, "50.00", false, null, "3.00", JUSTIFICATION)))
                .isInstanceOf(SessionPolicyConflictException.class);
        assertThat(written).isEmpty();
    }
}
