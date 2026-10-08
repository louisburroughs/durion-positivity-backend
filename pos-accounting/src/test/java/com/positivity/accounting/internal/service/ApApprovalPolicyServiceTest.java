package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.ApApprovalPolicyRequest;
import com.positivity.accounting.internal.dto.ApApprovalPolicyResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The AP approval policy GET and PUT (CAP:550 S13, #2510; §5.5, §8.2; AC8, AC9, AC12): validation, writing only what
 * changes with one audit row each, the requestId replay, and the history. PostgreSQL row locks, row-level security and
 * the replay against the real table are ApApprovalPolicyPostgresIT's.
 */
@DisplayName("ApApprovalPolicyService: GET, PUT, replay and history (#2510)")
class ApApprovalPolicyServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T15:00:00Z"), ZoneOffset.UTC);
    private static final UUID REQUEST = UUID.fromString("0199c0de-7a1b-7c2d-8e3f-4a5b6c7d8e9f");

    private final AccountingConfigurationRepository configuration = mock();
    private final AccountingAuditLogRepository auditLogs = mock();
    private final FunctionalCurrency usd = new FunctionalCurrency(new LedgerCurrency("USD"));
    private final Map<String, AccountingConfiguration> stored = new HashMap<>();
    private final List<AccountingAuditLog> written = new ArrayList<>();
    private ApApprovalPolicyServiceImpl service;

    @BeforeEach
    void wire() {
        ApApprovalPolicy policy = new ApApprovalPolicy(configuration, usd);
        service = new ApApprovalPolicyServiceImpl(
                CLOCK, policy, configuration, auditLogs, usd, mock(ApApprovalPolicyLock.class));
        when(configuration.findByConfigKeyIn(anyCollection())).thenAnswer(inv -> List.copyOf(stored.values()));
        when(configuration.findWithLockByConfigKey(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(stored.get(inv.getArgument(0, String.class))));
        when(configuration.save(any(AccountingConfiguration.class))).thenAnswer(inv -> {
            AccountingConfiguration row = inv.getArgument(0);
            if (row.getConfigId() == null) {
                row.setConfigId(UUID.randomUUID());
            }
            stored.put(row.getConfigKey(), row);
            return row;
        });
        when(auditLogs.save(any(AccountingAuditLog.class))).thenAnswer(inv -> {
            written.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(auditLogs.findByOperation(eq("AP_APPROVAL_POLICY_SET"), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.copyOf(written).reversed()));
        when(auditLogs.existsByOperationAndNewValueContaining(eq("AP_APPROVAL_POLICY_SET"), anyString()))
                .thenAnswer(inv ->
                        written.stream().anyMatch(row -> row.getNewValue().contains(inv.getArgument(1, String.class))));
        signIn("controller.cfo", "ROLE_CONTROLLER", "accounting:ap_approval_policy:manage");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String username, String... authorities) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                Stream.of(authorities).map(SimpleGrantedAuthority::new).toList());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }

    private static ApApprovalPolicyRequest put(
            String clerk, String auto, Boolean creator, String terms, String justification, UUID requestId) {
        return new ApApprovalPolicyRequest(
                clerk == null ? null : new BigDecimal(clerk),
                auto == null ? null : new BigDecimal(auto),
                clerk == null && auto == null ? null : "USD",
                creator,
                null,
                terms,
                justification,
                requestId);
    }

    @Test
    @DisplayName("AC9: a fresh tenant reads the defaults; a stored 'NET 30' reads as NET30")
    void defaults() {
        ApApprovalPolicyResponse fresh = service.get(0, 20);
        assertThat(fresh.clerkApprovalLimit()).isEqualByComparingTo("0.00");
        assertThat(fresh.autoApprovalLimit()).isEqualByComparingTo("0.00");
        assertThat(fresh.currencyCode()).isEqualTo("USD");
        assertThat(fresh.allowCreatorApproval()).isFalse();
        assertThat(fresh.allowApproverPayment()).isFalse();
        assertThat(fresh.defaultTerms()).isEqualTo("NET30");
        assertThat(fresh.asOf()).isEqualTo(CLOCK.instant());
        assertThat(fresh.history()).isEmpty();

        AccountingConfiguration row = new AccountingConfiguration();
        row.setConfigKey("AP_DEFAULT_TERMS");
        row.setConfigValue("NET 30");
        stored.put("AP_DEFAULT_TERMS", row);
        assertThat(service.get(0, 20).defaultTerms()).isEqualTo("NET30");
    }

    @Test
    @DisplayName("AC9/AC12: three changes list newest first with the actor, the roles, old to new and the reason; an"
            + " unchanged setting writes nothing")
    void writesOnlyWhatChangesAndListsTheHistory() {
        service.set(put("2500", "500", null, null, "Routine parts bills up to 2,500", REQUEST));
        ApApprovalPolicyResponse after =
                service.set(put("2500.00", null, null, "NET15", "Vendors moved to fifteen days", UUID.randomUUID()));

        assertThat(written).extracting(AccountingAuditLog::getOldValue).containsExactly("0.00", "0.00", "NET30");
        assertThat(after.clerkApprovalLimit()).isEqualByComparingTo("2500.00");
        assertThat(after.autoApprovalLimit()).isEqualByComparingTo("500.00");
        assertThat(after.defaultTerms()).isEqualTo("NET15");
        assertThat(after.history())
                .extracting(
                        ApApprovalPolicyResponse.HistoryRow::setting,
                        ApApprovalPolicyResponse.HistoryRow::oldValue,
                        ApApprovalPolicyResponse.HistoryRow::newValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("AP_DEFAULT_TERMS", "NET30", "NET15"),
                        org.assertj.core.groups.Tuple.tuple("AP_AUTO_APPROVAL_LIMIT", "0.00", "500.00"),
                        org.assertj.core.groups.Tuple.tuple("AP_CLERK_APPROVAL_LIMIT", "0.00", "2500.00"));
        assertThat(after.history()).allSatisfy(row -> {
            assertThat(row.changedBy()).isEqualTo("controller.cfo");
            assertThat(row.changedByRoles()).containsExactly("CONTROLLER");
        });
        assertThat(after.history().get(0).justification()).isEqualTo("Vendors moved to fifteen days");
    }

    @Test
    @DisplayName("AC8: a replay with the same requestId after another manager changed the limit writes nothing and"
            + " returns the current policy")
    void replayWritesNothing() {
        service.set(put("2500", null, null, null, "Routine parts bills up to 2,500", REQUEST));
        signIn("gm.gary", "ROLE_GENERAL_MANAGER", "accounting:ap_approval_policy:manage");
        service.set(put("3000", null, null, null, "Busier season, more parts bills", UUID.randomUUID()));

        signIn("controller.cfo", "ROLE_CONTROLLER", "accounting:ap_approval_policy:manage");
        ApApprovalPolicyResponse replay =
                service.set(put("2500", null, null, null, "Routine parts bills up to 2,500", REQUEST));

        assertThat(replay.clerkApprovalLimit()).isEqualByComparingTo("3000.00");
        assertThat(written).hasSize(2);
        assertThat(written.stream().filter(row -> row.getNewValue().contains("requestId=" + REQUEST)))
                .hasSize(1);
    }

    @Test
    @DisplayName("AC8: an automatic limit above the clerk limit, NET_30, a 9-character justification: 400, nothing"
            + " written")
    void refusals() {
        assertThatThrownBy(() -> service.set(put("300", "500", null, null, "Ten chars plus", REQUEST)))
                .isInstanceOfSatisfying(VendorBillException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                    assertThat(e.getFieldErrors())
                            .extracting(VendorBillException.FieldError::field)
                            .containsExactly("autoApprovalLimit");
                });
        assertThatThrownBy(() -> service.set(put(null, null, null, "NET_30", "Ten chars plus", REQUEST)))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getFieldErrors())
                                .extracting(VendorBillException.FieldError::field)
                                .containsExactly("defaultTerms"));
        assertThatThrownBy(() -> service.set(put("300", null, null, null, "Nine char", REQUEST)))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.JUSTIFICATION_REQUIRED));
        assertThatThrownBy(() -> service.set(put("-1", null, null, null, "Ten chars plus", REQUEST)))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getFieldErrors())
                                .extracting(VendorBillException.FieldError::field)
                                .containsExactly("clerkApprovalLimit"));
        assertThatThrownBy(() -> service.set(put("300", null, null, null, "Ten chars plus", null)))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getFieldErrors())
                                .extracting(VendorBillException.FieldError::field)
                                .containsExactly("requestId"));
        verify(configuration, never()).save(any());
        verify(auditLogs, never()).save(any());
    }

    @Test
    @DisplayName("ADR-0067: a limit needs the functional currencyCode: missing is 400, another one 422")
    void currency() {
        ApApprovalPolicyRequest missing = new ApApprovalPolicyRequest(
                new BigDecimal("300"), null, null, null, null, null, "Ten chars plus", REQUEST);
        assertThatThrownBy(() -> service.set(missing))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getFieldErrors())
                                .extracting(VendorBillException.FieldError::field)
                                .containsExactly("currencyCode"));
        ApApprovalPolicyRequest cad = new ApApprovalPolicyRequest(
                new BigDecimal("300"), null, "CAD", null, null, null, "Ten chars plus", REQUEST);
        assertThatThrownBy(() -> service.set(cad)).isInstanceOf(CurrencyNotSupportedException.class);
        ApApprovalPolicyRequest switchOnly =
                new ApApprovalPolicyRequest(null, null, null, true, null, null, "Ten chars plus", REQUEST);
        service.set(switchOnly);
        assertThat(written)
                .singleElement()
                .satisfies(row ->
                        assertThat(row.getNewValue()).startsWith("setting=AP_ALLOW_CREATOR_APPROVAL;value=true"));
    }

    @Test
    @DisplayName("AC12: the audit names the caller from the security context")
    void actorFromTheContext() {
        signIn("gm.gary", "ROLE_GENERAL_MANAGER", "accounting:ap_approval_policy:manage");
        service.set(put("1000", null, null, null, "Smaller shop, smaller limit", REQUEST));
        ArgumentCaptor<AccountingAuditLog> row = ArgumentCaptor.forClass(AccountingAuditLog.class);
        verify(auditLogs).save(row.capture());
        assertThat(row.getValue().getUserId()).isEqualTo("gm.gary");
        assertThat(row.getValue().getEntityType()).isEqualTo("ACCOUNTING_CONFIGURATION");
        assertThat(row.getValue().getNewValue())
                .isEqualTo("setting=AP_CLERK_APPROVAL_LIMIT;value=1000.00;roles=GENERAL_MANAGER;requestId=" + REQUEST);
    }
}
