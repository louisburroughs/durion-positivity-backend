package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.EnableTemplateAddOnRequest;
import com.positivity.accounting.internal.dto.TenantTemplateStatusResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.entity.AccountingTemplateEntry;
import com.positivity.accounting.internal.entity.AccountingTemplateState;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.TemplateEntryKind;
import com.positivity.accounting.internal.enums.TemplateEntryOutcome;
import com.positivity.accounting.internal.enums.TemplateEntryReason;
import com.positivity.accounting.internal.enums.TenantTemplateState;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.accounting.internal.repository.AccountingTemplateEntryRepository;
import com.positivity.accounting.internal.repository.AccountingTemplateStateRepository;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** The status read's states and the add-on choice (#2526). */
@DisplayName("TenantTemplateServiceImpl")
class TenantTemplateServiceImplTest {

    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-0000000000b2");
    private static final UUID REQUEST_ID = UUID.fromString("019a0000-0000-7000-8000-000000000009");
    private static final Instant APPLIED_AT = Instant.parse("2026-10-05T12:00:00Z");
    private static final AccountingTemplate SNAPSHOT = AccountingTemplate.of(List.of(
            new AccountingTemplate.Account("4000", "Service Revenue", AccountType.REVENUE, null, false, null, null)));

    private final AccountingTemplateStateRepository states = mock(AccountingTemplateStateRepository.class);
    private final AccountingTemplateEntryRepository entryRecords = mock(AccountingTemplateEntryRepository.class);
    private final AccountingConfigurationRepository configuration = mock(AccountingConfigurationRepository.class);
    private final AccountingAuditLogRepository auditLogs = mock(AccountingAuditLogRepository.class);
    private final AccountingTemplateStateLock stateLock = mock(AccountingTemplateStateLock.class);
    private final AccountingTemplateReader reader = mock(AccountingTemplateReader.class);
    private final AccountingTenantProvisioner provisioner = mock(AccountingTenantProvisioner.class);
    private final RetreadPlantAddOnSource addOn = mock(RetreadPlantAddOnSource.class);

    private TenantTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TenantTemplateServiceImpl(
                states, entryRecords, configuration, auditLogs, stateLock, reader, provisioner, addOn);
        when(reader.loaded()).thenReturn(Optional.of(SNAPSHOT));
        when(provisioner.templateFor(TENANT, SNAPSHOT)).thenReturn(SNAPSHOT);
        when(entryRecords.findByOutcomeInOrderByEntryKeyAsc(any())).thenReturn(List.of());
        when(configuration.findByConfigKey(any())).thenReturn(Optional.empty());
        when(configuration.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TenantContext.bind(TENANT);
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                "carol.controller", "n/a", List.of(new SimpleGrantedAuthority("accounting:coa:create")));
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "carol.controller"));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("NOT_PROVISIONED when the template was never applied, also when only the lock row exists")
    void notProvisioned() {
        when(states.findCurrent()).thenReturn(Optional.empty());
        assertThat(service.status().state()).isEqualTo(TenantTemplateState.NOT_PROVISIONED);

        when(states.findCurrent()).thenReturn(Optional.of(new AccountingTemplateState()));
        TenantTemplateStatusResponse status = service.status();
        assertThat(status.state()).isEqualTo(TenantTemplateState.NOT_PROVISIONED);
        assertThat(status.lastAppliedAt()).isNull();
        assertThat(status.attention()).isEmpty();
    }

    @Test
    @DisplayName("UP_TO_DATE with counts when the applied fingerprint is the tenant's template")
    void upToDate() {
        when(states.findCurrent()).thenReturn(Optional.of(applied(SNAPSHOT.fingerprint())));

        TenantTemplateStatusResponse status = service.status();

        assertThat(status.state()).isEqualTo(TenantTemplateState.UP_TO_DATE);
        assertThat(status.lastAppliedAt()).isEqualTo(APPLIED_AT);
        assertThat(status.counts()).isEqualTo(new TenantTemplateStatusResponse.Counts(180, 4, 1, 0, 0));
        assertThat(status.retreadPlantAddOn()).isFalse();
    }

    @Test
    @DisplayName("PENDING while the template is newer than the last apply")
    void pending() {
        when(states.findCurrent()).thenReturn(Optional.of(applied("an-older-fingerprint")));

        assertThat(service.status().state()).isEqualTo(TenantTemplateState.PENDING);
    }

    @Test
    @DisplayName("without a template read in this process the status reports what was last applied")
    void reportsLastApplyWhenNoTemplateWasRead() {
        when(reader.loaded()).thenReturn(Optional.empty());
        when(states.findCurrent()).thenReturn(Optional.of(applied("an-older-fingerprint")));

        assertThat(service.status().state()).isEqualTo(TenantTemplateState.UP_TO_DATE);
        verify(reader, never()).snapshot();
    }

    @Test
    @DisplayName("NEEDS_ATTENTION lists each open entry in business words, without ids")
    void needsAttention() {
        when(states.findCurrent()).thenReturn(Optional.of(applied(SNAPSHOT.fingerprint())));
        AccountingTemplateEntry conflict = new AccountingTemplateEntry();
        conflict.setEntryKey("ACCOUNT:6295");
        conflict.setKind(TemplateEntryKind.ACCOUNT);
        conflict.setOutcome(TemplateEntryOutcome.CONFLICT);
        conflict.setReason(TemplateEntryReason.ACCOUNT_DIFFERS);
        conflict.setTargetRowId(UUID.randomUUID());
        conflict.setTemplateValue("6295 Staff Meals & Refreshments, expense");
        conflict.setTenantValue("6295 Tire disposal, expense");
        when(entryRecords.findByOutcomeInOrderByEntryKeyAsc(
                        List.of(TemplateEntryOutcome.CONFLICT, TemplateEntryOutcome.WITHHELD)))
                .thenReturn(List.of(conflict));

        TenantTemplateStatusResponse status = service.status();

        assertThat(status.state()).isEqualTo(TenantTemplateState.NEEDS_ATTENTION);
        assertThat(status.attention())
                .containsExactly(new TenantTemplateStatusResponse.AttentionItem(
                        "ACCOUNT:6295",
                        TemplateEntryKind.ACCOUNT,
                        TemplateEntryReason.ACCOUNT_DIFFERS,
                        "6295 Staff Meals & Refreshments, expense",
                        "6295 Tire disposal, expense"));
    }

    @Test
    @DisplayName("turning the add-on on records the choice, audits it with the caller, then reconciles the tenant")
    void enablesTheAddOn() {
        when(states.findCurrent()).thenReturn(Optional.of(applied(SNAPSHOT.fingerprint())));
        when(addOn.appliesTo(TENANT)).thenReturn(false, true);

        TenantTemplateStatusResponse status = service.enableRetreadPlantAddOn(
                new EnableTemplateAddOnRequest("We run a retread plant at the Tulsa shop", REQUEST_ID));

        InOrder order = inOrder(stateLock, configuration, auditLogs, provisioner);
        order.verify(stateLock).acquire();
        ArgumentCaptor<AccountingConfiguration> choice = ArgumentCaptor.forClass(AccountingConfiguration.class);
        order.verify(configuration).save(choice.capture());
        ArgumentCaptor<AccountingAuditLog> audit = ArgumentCaptor.forClass(AccountingAuditLog.class);
        order.verify(auditLogs).save(audit.capture());
        order.verify(provisioner).reconcile(TENANT, SNAPSHOT);
        assertThat(choice.getValue().getConfigKey()).isEqualTo("RETREAD_PLANT_ADD_ON");
        assertThat(choice.getValue().getConfigValue()).isEqualTo("true");
        assertThat(audit.getValue().getOperation()).isEqualTo("TENANT_TEMPLATE_ADD_ON_ENABLE");
        assertThat(audit.getValue().getUserId()).isEqualTo("carol.controller");
        assertThat(audit.getValue().getJustification()).isEqualTo("We run a retread plant at the Tulsa shop");
        assertThat(audit.getValue().getNewValue()).contains(REQUEST_ID.toString());
        assertThat(status.retreadPlantAddOn()).isTrue();
    }

    @Test
    @DisplayName("a replay, or an add-on already on, changes nothing and returns the current state")
    void replayChangesNothing() {
        when(states.findCurrent()).thenReturn(Optional.of(applied(SNAPSHOT.fingerprint())));
        when(addOn.appliesTo(TENANT)).thenReturn(true);

        TenantTemplateStatusResponse status = service.enableRetreadPlantAddOn(
                new EnableTemplateAddOnRequest("We run a retread plant at the Tulsa shop", REQUEST_ID));

        verify(configuration, never()).save(any());
        verify(auditLogs, never()).save(any());
        verify(provisioner, never()).reconcile(any(), any());
        assertThat(status.state()).isEqualTo(TenantTemplateState.UP_TO_DATE);
        assertThat(status.retreadPlantAddOn()).isTrue();
    }

    @Test
    @DisplayName("with no template read in this process the choice is recorded and no tenant switch happens")
    void recordsTheChoiceWithoutATemplate() {
        when(reader.loaded()).thenReturn(Optional.empty());
        when(states.findCurrent()).thenReturn(Optional.of(applied(SNAPSHOT.fingerprint())));
        when(addOn.appliesTo(TENANT)).thenReturn(false, true);

        service.enableRetreadPlantAddOn(
                new EnableTemplateAddOnRequest("We run a retread plant at the Tulsa shop", REQUEST_ID));

        verify(configuration).save(any());
        verify(auditLogs).save(any());
        verify(reader, never()).snapshot();
        verify(provisioner, never()).reconcile(any(), any());
    }

    private static AccountingTemplateState applied(String fingerprint) {
        AccountingTemplateState state = new AccountingTemplateState();
        state.setTemplateFingerprint(fingerprint);
        state.setLastAppliedAt(APPLIED_AT);
        state.setCreatedCount(180);
        state.setAdoptedCount(4);
        state.setRefreshedCount(1);
        return state;
    }
}
