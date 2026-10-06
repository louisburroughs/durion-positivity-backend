package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.TemplateEntryOutcome;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.beans.factory.ObjectProvider;

/** One provisioning transaction (#2526): which entries a tenant receives, the eventId, the guards. */
@DisplayName("AccountingTenantProvisioner")
class AccountingTenantProvisionerTest {

    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-0000000000b2");
    private static final String EVENT_ID = "01990000-0000-7000-8000-0000000000e1";
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final AccountingTemplate.Account GENERIC =
            new AccountingTemplate.Account("4000", "Service Revenue", AccountType.REVENUE, null, false, null, null);
    private static final AccountingTemplate.Account ADD_ON = new AccountingTemplate.Account(
            "6350", "Retread Curing Consumables", AccountType.EXPENSE, null, false, null, null);
    private static final AccountingTemplate SNAPSHOT = AccountingTemplate.of(List.of(GENERIC, ADD_ON));

    private final AccountingTemplateApplier applier = mock(AccountingTemplateApplier.class);
    private final DataInitializationService policyDefaults = mock(DataInitializationService.class);
    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final AccountingTemplateSource generic = mock(AccountingTemplateSource.class);
    private final AccountingTemplateSource addOn = mock(AccountingTemplateSource.class);
    private final AccountingTemplateStateLock stateLock = mock(AccountingTemplateStateLock.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();

    private AccountingTenantProvisioner provisioner;

    @BeforeEach
    void setUp() {
        when(generic.owns(any())).thenAnswer(invocation -> invocation.getArgument(0) == GENERIC);
        when(generic.appliesTo(any())).thenReturn(true);
        when(addOn.owns(any())).thenAnswer(invocation -> invocation.getArgument(0) == ADD_ON);
        when(applier.apply(any(), any()))
                .thenReturn(new AccountingTemplateApplier.Result(
                        true,
                        Map.of(TemplateEntryOutcome.CREATED, 1),
                        Map.of(TemplateEntryOutcome.CREATED, 1),
                        List.of()));
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(meters);
        provisioner = new AccountingTenantProvisioner(
                stateLock,
                applier,
                List.of(generic, addOn),
                policyDefaults,
                processedEvents,
                Clock.fixed(NOW, ZoneOffset.UTC),
                meterProvider);
        TenantContext.bind(TENANT);
    }

    @AfterEach
    void clearBinding() {
        TenantContext.clear();
    }

    @Test
    @DisplayName(
            "a tenant.created fact applies the template, then the policy defaults, then records the eventId as owner tenant")
    void provisionsFromAnEvent() {
        Optional<AccountingTemplateApplier.Result> result = provisioner.provision(TENANT, EVENT_ID, SNAPSHOT);

        assertThat(result).isPresent();
        InOrder order = inOrder(applier, policyDefaults, processedEvents);
        order.verify(processedEvents).existsById(EVENT_ID);
        order.verify(applier).apply(any(), any());
        order.verify(policyDefaults).seedPolicyDefaults();
        ArgumentCaptor<ProcessedEvent> recorded = ArgumentCaptor.forClass(ProcessedEvent.class);
        order.verify(processedEvents).save(recorded.capture());
        assertThat(recorded.getValue().getEventId()).isEqualTo(EVENT_ID);
        assertThat(recorded.getValue().getOwner()).isEqualTo("tenant");
        assertThat(recorded.getValue().getProcessedAt()).isEqualTo(NOW);
        assertThat(meters.get("accounting.tenant_template.entries")
                        .tag("outcome", "CREATED")
                        .tag("path", "event")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("the tenant's state row is locked before the eventId is re-checked and before the sources are asked")
    void locksBeforeDecidingAnything() {
        provisioner.provision(TENANT, EVENT_ID, SNAPSHOT);
        provisioner.reconcile(TENANT, SNAPSHOT);

        InOrder order = inOrder(stateLock, processedEvents, addOn, applier);
        order.verify(stateLock).acquire();
        order.verify(processedEvents).existsById(EVENT_ID);
        order.verify(addOn).appliesTo(TENANT);
        order.verify(applier).apply(any(), any());
        order.verify(stateLock).acquire();
        order.verify(addOn).appliesTo(TENANT);
        order.verify(applier).apply(any(), any());
    }

    @Test
    @DisplayName("a tenant receives only the sources that apply to it: no add-on entry unless it chose the add-on")
    void appliesOnlyTheSourcesThatApply() {
        ArgumentCaptor<AccountingTemplate> applied = ArgumentCaptor.forClass(AccountingTemplate.class);

        when(addOn.appliesTo(TENANT)).thenReturn(false);
        provisioner.provision(TENANT, null, SNAPSHOT);
        when(addOn.appliesTo(TENANT)).thenReturn(true);
        provisioner.provision(TENANT, null, SNAPSHOT);

        verify(applier, org.mockito.Mockito.times(2)).apply(any(), applied.capture());
        assertThat(applied.getAllValues().get(0).entries()).containsExactly(GENERIC);
        assertThat(applied.getAllValues().get(1).entries()).containsExactly(GENERIC, ADD_ON);
        assertThat(applied.getAllValues().get(0).fingerprint())
                .as("choosing an add-on changes the tenant's template, so the next run is not short-circuited")
                .isNotEqualTo(applied.getAllValues().get(1).fingerprint());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("an eventId recorded while this run waited is found inside the transaction and nothing runs")
    void recheckSkipsARecordedEvent() {
        when(processedEvents.existsById(EVENT_ID)).thenReturn(true);

        assertThat(provisioner.provision(TENANT, EVENT_ID, SNAPSHOT)).isEmpty();

        verifyNoInteractions(applier, policyDefaults);
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("an empty template is refused and the eventId is not recorded")
    void emptyTemplateIsRefused() {
        assertThatThrownBy(() -> provisioner.provision(TENANT, EVENT_ID, AccountingTemplate.of(List.of())))
                .isInstanceOf(EmptyAccountingTemplateException.class);

        verifyNoInteractions(applier, policyDefaults);
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("the platform tenant is never provisioned, and a tenant is provisioned only under its own binding")
    void refusesThePlatformTenantAndAForeignBinding() {
        TenantContext.bind(PlatformTenant.ID);
        assertThatThrownBy(() -> provisioner.provision(PlatformTenant.ID, null, SNAPSHOT))
                .isInstanceOf(IllegalArgumentException.class);

        TenantContext.bind(UUID.fromString("01900000-0000-7000-8000-000000000001"));
        assertThatThrownBy(() -> provisioner.provision(TENANT, null, SNAPSHOT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must run under that tenant's binding");

        TenantContext.clear();
        assertThatThrownBy(() -> provisioner.reconcile(TENANT, SNAPSHOT)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(applier, policyDefaults);
    }

    @Test
    @DisplayName("a skipped tenant.created is recorded once")
    void recordsASkippedEventOnce() {
        provisioner.recordSkipped(EVENT_ID);
        when(processedEvents.existsById(EVENT_ID)).thenReturn(true);
        provisioner.recordSkipped(EVENT_ID);

        verify(processedEvents, org.mockito.Mockito.times(1)).save(any());
    }
}
