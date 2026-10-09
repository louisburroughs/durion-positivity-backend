package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
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
    private final ActorDisplayNames actorNames = mock();
    private final FunctionalCurrency usd = new FunctionalCurrency(new LedgerCurrency("USD"));
    private final Map<String, AccountingConfiguration> stored = new HashMap<>();
    private final List<AccountingAuditLog> written = new ArrayList<>();
    private ApApprovalPolicyServiceImpl service;

    @BeforeEach
    void wire() {
        ApApprovalPolicy policy = new ApApprovalPolicy(configuration, usd);
        service = new ApApprovalPolicyServiceImpl(
                CLOCK, policy, configuration, auditLogs, usd, mock(ApApprovalPolicyLock.class), actorNames);
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
        when(auditLogs.findByOperation(anyString(), any(Pageable.class))).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(1);
            List<AccountingAuditLog> rows =
                    rows(inv.getArgument(0, String.class)).reversed();
            int from = (int) Math.min(pageable.getOffset(), rows.size());
            int to = Math.min(from + pageable.getPageSize(), rows.size());
            return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
        });
        when(auditLogs.existsByOperationAndEntityId(anyString(), any(UUID.class)))
                .thenAnswer(inv -> rows(inv.getArgument(0, String.class)).stream()
                        .anyMatch(row -> row.getEntityId().equals(inv.getArgument(1))));
        signIn("controller.cfo", "ROLE_CONTROLLER", "accounting:ap_approval_policy:manage");
    }

    /** The rows written under {@code operation}, oldest first. */
    private List<AccountingAuditLog> rows(String operation) {
        return written.stream()
                .filter(row -> row.getOperation().equals(operation))
                .toList();
    }

    private List<AccountingAuditLog> changes() {
        return rows("AP_APPROVAL_POLICY_SET");
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

        assertThat(changes()).extracting(AccountingAuditLog::getOldValue).containsExactly("0.00", "0.00", "NET30");
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
    @DisplayName("#2670 AC 6 and AC 8: a 20-row history page with 5 distinct users resolves their names in one call;"
            + " a known user serves changedByName, an unknown one null, never the username")
    void historyNamesInOneCall() {
        for (int i = 0; i < 20; i++) {
            AccountingAuditLog row = new AccountingAuditLog();
            row.setOperation("AP_APPROVAL_POLICY_SET");
            row.setEntityId(UUID.randomUUID());
            row.setUserId(i % 5 == 0 ? "controller.cfo" : "user." + (i % 5));
            row.setTimestamp(CLOCK.instant().plusSeconds(i));
            row.setNewValue("setting=AP_CLERK_APPROVAL_LIMIT;value=" + i + ".00;roles=CONTROLLER");
            row.setOldValue("0.00");
            row.setJustification("Routine parts bills");
            written.add(row);
        }
        when(actorNames.namesOf(anyCollection())).thenReturn(Map.of("controller.cfo", "Dana Reyes"));

        ApApprovalPolicyResponse page = service.get(0, 20);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Collection<String>> asked =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(actorNames, org.mockito.Mockito.times(1)).namesOf(asked.capture());
        assertThat(java.util.Set.copyOf(asked.getValue()))
                .containsExactlyInAnyOrder("controller.cfo", "user.1", "user.2", "user.3", "user.4");
        assertThat(page.history()).hasSize(20);
        assertThat(page.history())
                .filteredOn(row -> row.changedBy().equals("controller.cfo"))
                .hasSize(4)
                .allSatisfy(row -> assertThat(row.changedByName()).isEqualTo("Dana Reyes"));
        assertThat(page.history())
                .filteredOn(row -> !row.changedBy().equals("controller.cfo"))
                .allSatisfy(row -> assertThat(row.changedByName()).isNull());
        assertThat(page.history().get(0).toString()).doesNotContain("Dana Reyes");
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
        assertThat(changes()).hasSize(2);
        assertThat(changes().stream().filter(row -> row.getNewValue().contains("requestId=" + REQUEST)))
                .hasSize(1);
        assertThat(rows("AP_APPROVAL_POLICY_REQUEST"))
                .extracting(AccountingAuditLog::getEntityId)
                .containsOnlyOnce(REQUEST);
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
        assertThat(changes())
                .singleElement()
                .satisfies(row ->
                        assertThat(row.getNewValue()).startsWith("setting=AP_ALLOW_CREATOR_APPROVAL;value=true"));
    }

    @Test
    @DisplayName("AC12: the audit names the caller from the security context")
    void actorFromTheContext() {
        signIn("gm.gary", "ROLE_GENERAL_MANAGER", "accounting:ap_approval_policy:manage");
        service.set(put("1000", null, null, null, "Smaller shop, smaller limit", REQUEST));
        assertThat(changes()).hasSize(1);
        AccountingAuditLog row = changes().getFirst();
        assertThat(row.getUserId()).isEqualTo("gm.gary");
        assertThat(row.getEntityType()).isEqualTo("ACCOUNTING_CONFIGURATION");
        assertThat(row.getNewValue())
                .isEqualTo("setting=AP_CLERK_APPROVAL_LIMIT;value=1000.00;roles=GENERAL_MANAGER;requestId=" + REQUEST);
    }

    private void storedRow(String key, String value) {
        AccountingConfiguration row = new AccountingConfiguration();
        row.setConfigKey(key);
        row.setConfigValue(value);
        row.setConfigId(UUID.randomUUID());
        stored.put(key, row);
    }

    @Test
    @DisplayName("Ruling 2(c)/AC8: lowering the clerk limit below the stored automatic limit lowers it too, on its own"
            + " row tagged cause=AP_CLERK_APPROVAL_LIMIT; raising it later never raises the automatic limit")
    void loweringTheClerkLimitLowersTheAutomaticLimit() {
        service.set(put("2500", "500", null, null, "Routine parts bills up to 2,500", REQUEST));

        ApApprovalPolicyResponse lowered =
                service.set(put("300", null, null, null, "Tighter after the audit", UUID.randomUUID()));

        assertThat(lowered.clerkApprovalLimit()).isEqualByComparingTo("300.00");
        assertThat(lowered.autoApprovalLimit()).isEqualByComparingTo("300.00");
        assertThat(changes().getLast().getNewValue())
                .startsWith("setting=AP_AUTO_APPROVAL_LIMIT;value=300.00;")
                .contains(";cause=AP_CLERK_APPROVAL_LIMIT;");
        assertThat(changes().getLast().getOldValue()).isEqualTo("500.00");
        assertThat(lowered.history().getFirst().setting()).isEqualTo("AP_AUTO_APPROVAL_LIMIT");

        ApApprovalPolicyResponse raised =
                service.set(put("1000", null, null, null, "Back to a looser clerk limit", UUID.randomUUID()));
        assertThat(raised.autoApprovalLimit()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("Ruling 2(c): a clerk limit of 0 turns automatic approval off; lowering both at once writes both as"
            + " sent; a stored automatic limit above the clerk limit is repaired by the next PUT")
    void clerkZeroAndLoweringBoth() {
        service.set(put("2500", "500", null, null, "Routine parts bills up to 2,500", REQUEST));
        ApApprovalPolicyResponse off =
                service.set(put("0", null, null, null, "Everything to a controller", UUID.randomUUID()));
        assertThat(off.autoApprovalLimit()).isEqualByComparingTo("0.00");

        service.set(put("400", "100", null, null, "Both limits set again", UUID.randomUUID()));
        ApApprovalPolicyResponse both =
                service.set(put("200", "50", null, null, "Both limits lowered", UUID.randomUUID()));
        assertThat(both.clerkApprovalLimit()).isEqualByComparingTo("200.00");
        assertThat(both.autoApprovalLimit()).isEqualByComparingTo("50.00");
        assertThat(changes().subList(changes().size() - 2, changes().size()))
                .allSatisfy(row -> assertThat(row.getNewValue()).doesNotContain("cause="));

        storedRow("AP_CLERK_APPROVAL_LIMIT", "300.00");
        storedRow("AP_AUTO_APPROVAL_LIMIT", "500.00");
        ApApprovalPolicyResponse repaired =
                service.set(put(null, null, null, "NET15", "Terms change, limits repaired", UUID.randomUUID()));
        assertThat(repaired.autoApprovalLimit()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("Ruling 2: an explicit automatic limit above the resulting clerk limit stays 400, also when only the"
            + " automatic limit is sent")
    void explicitAutomaticAboveTheClerkLimitIsRefused() {
        service.set(put("300", null, null, null, "Clerks approve up to 300", REQUEST));
        assertThatThrownBy(() -> service.set(put(null, "500", null, null, "Automatic above it", UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getFieldErrors())
                                .extracting(VendorBillException.FieldError::field)
                                .containsExactly("autoApprovalLimit"));
    }

    @Test
    @DisplayName("#2622 M1: a hostile role name cannot add or shadow a field of the history row, nor fake a replay")
    void hostileRoleNameIsEncoded() {
        UUID victim = UUID.fromString("0199c0de-7a1b-7c2d-8e3f-000000000bad");
        signIn(
                "gm.gary",
                "ROLE_X;value=0.00;setting=AP_DEFAULT_TERMS;requestId=" + victim,
                "accounting:ap_approval_policy:manage");
        service.set(put("1000", null, null, null, "Smaller shop, smaller limit", REQUEST));

        ApApprovalPolicyResponse.HistoryRow row = service.get(0, 20).history().getFirst();
        assertThat(row.setting()).isEqualTo("AP_CLERK_APPROVAL_LIMIT");
        assertThat(row.newValue()).isEqualTo("1000.00");
        assertThat(row.changedByRoles()).containsExactly("X;value=0.00;setting=AP_DEFAULT_TERMS;requestId=" + victim);

        ApApprovalPolicyResponse notAReplay =
                service.set(put("2000", null, null, null, "A different request, not a replay", victim));
        assertThat(notAReplay.clerkApprovalLimit()).isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("#2622 M1: rows without escapes still read as they are; the first occurrence of a field wins")
    void decodeFallsBackAndFirstWins() {
        assertThat(ApApprovalPolicyServiceImpl.decode(
                        "setting=AP_CLERK_APPROVAL_LIMIT;value=2500.00;roles=CONTROLLER;requestId=r;value=0.00"))
                .containsEntry("value", "2500.00")
                .containsEntry("roles", "CONTROLLER");
        assertThat(ApApprovalPolicyServiceImpl.decode(ApApprovalPolicyServiceImpl.encode(Map.of("roles", "A;B=C,D%"))))
                .containsEntry("roles", "A;B=C,D%");
    }

    @Test
    @DisplayName("#2622 M2: A sends a no-op, B changes the limit, A retries: the retry is a replay and writes nothing")
    void noOpRequestIdIsRecorded() {
        service.set(put("2500", null, null, null, "Routine parts bills up to 2,500", UUID.randomUUID()));
        UUID noOp = UUID.fromString("0199c0de-7a1b-7c2d-8e3f-00000000a001");
        service.set(put("2500", null, null, null, "Manager A saves without a change", noOp));
        assertThat(changes()).hasSize(1);
        signIn("gm.gary", "ROLE_GENERAL_MANAGER", "accounting:ap_approval_policy:manage");
        service.set(put("3000", null, null, null, "Manager B raises the limit", UUID.randomUUID()));

        signIn("controller.cfo", "ROLE_CONTROLLER", "accounting:ap_approval_policy:manage");
        ApApprovalPolicyResponse retry =
                service.set(put("2500", null, null, null, "Manager A saves without a change", noOp));

        assertThat(retry.clerkApprovalLimit()).isEqualByComparingTo("3000.00");
        assertThat(changes()).hasSize(2);
        assertThat(retry.history()).hasSize(2);
    }

    @Test
    @DisplayName("#2622 M3: a limit with more than 13 integer digits is 400 VALIDATION_ERROR, never expanded")
    void hugeLimitsAreRefused() {
        for (String huge : List.of("1E+600", "1E+100000000", "10000000000000")) {
            assertThatThrownBy(() -> service.set(put(huge, null, null, null, "Ten chars plus", REQUEST)))
                    .isInstanceOfSatisfying(VendorBillException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                        assertThat(e.getFieldErrors())
                                .extracting(VendorBillException.FieldError::field)
                                .containsExactly("clerkApprovalLimit");
                    });
        }
        service.set(put("9999999999999.99", null, null, null, "The largest limit allowed", REQUEST));
        assertThat(changes()).hasSize(1);
    }

    @Test
    @DisplayName("#2622 L3/L6: history pages: page 1 of size 2 lists the third change and historyTotal 3; a size over"
            + " 100 is clamped to 100")
    void historyPages() {
        service.set(put("2500", "500", null, "NET15", "Three settings at once", REQUEST));
        ApApprovalPolicyResponse page = service.get(1, 2);
        assertThat(page.historyPage()).isEqualTo(1);
        assertThat(page.historySize()).isEqualTo(2);
        assertThat(page.historyTotal()).isEqualTo(3);
        assertThat(page.history())
                .singleElement()
                .satisfies(row -> assertThat(row.setting()).isEqualTo("AP_CLERK_APPROVAL_LIMIT"));
        assertThat(service.get(0, 500).historySize()).isEqualTo(100);
    }
}
