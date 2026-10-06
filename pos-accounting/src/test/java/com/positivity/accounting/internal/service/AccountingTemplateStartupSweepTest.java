package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.enums.TemplateEntryOutcome;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.TenantRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;

/** The startup sweep (#2526): every registry tenant, each under its own binding, never blocking startup. */
@DisplayName("AccountingTemplateStartupSweep")
class AccountingTemplateStartupSweepTest {

    private static final UUID TENANT_A = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("01900000-0000-7000-8000-0000000000b2");
    private static final AccountingTemplate SNAPSHOT = AccountingTemplate.of(
            List.of(new AccountingTemplate.Category("INVOICE_REVENUE", "Invoice revenue recognition")));

    private final AccountingTemplateReader reader = mock(AccountingTemplateReader.class);
    private final AccountingTenantProvisioner provisioner = mock(AccountingTenantProvisioner.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void clearBinding() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("provisions each registry tenant bound to that tenant, with no eventId and the one snapshot")
    void provisionsEveryRegistryTenant() {
        when(reader.snapshot()).thenReturn(SNAPSHOT);
        List<Optional<UUID>> bound = new ArrayList<>();
        when(provisioner.provision(any(), isNull(), eq(SNAPSHOT))).thenAnswer(invocation -> {
            bound.add(TenantContext.current());
            return Optional.of(result(0));
        });

        sweepOver(TENANT_A, TENANT_B).run(new DefaultApplicationArguments());

        assertThat(bound).containsExactly(Optional.of(TENANT_A), Optional.of(TENANT_B));
        verify(reader).snapshot();
        assertThat(attentionGauge()).isZero();
    }

    @Test
    @DisplayName("one tenant's failure does not stop the next tenant or the startup")
    void continuesPastAFailingTenant() {
        when(reader.snapshot()).thenReturn(SNAPSHOT);
        when(provisioner.provision(eq(TENANT_A), isNull(), any())).thenThrow(new IllegalStateException("boom"));
        when(provisioner.provision(eq(TENANT_B), isNull(), any())).thenReturn(Optional.of(result(2)));

        AccountingTemplateStartupSweep sweep = sweepOver(TENANT_A, TENANT_B);
        assertThatCode(() -> sweep.run(new DefaultApplicationArguments())).doesNotThrowAnyException();

        verify(provisioner).provision(TENANT_B, null, SNAPSHOT);
        assertThat(attentionGauge()).as("tenant B has entries in conflict").isEqualTo(1.0);
    }

    @Test
    @DisplayName("an empty template is logged and provisions nobody, without blocking startup")
    void emptyTemplateDoesNotBlockStartup() {
        when(reader.snapshot()).thenThrow(new EmptyAccountingTemplateException());

        AccountingTemplateStartupSweep sweep = sweepOver(TENANT_A);
        assertThatCode(() -> sweep.run(new DefaultApplicationArguments())).doesNotThrowAnyException();

        verify(provisioner, never()).provision(any(), any(), any());
    }

    private AccountingTemplateStartupSweep sweepOver(UUID... tenants) {
        TenantRegistry registry = () -> List.of(tenants);
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(meters);
        return new AccountingTemplateStartupSweep(new TenantIterator(registry), reader, provisioner, meterProvider);
    }

    private double attentionGauge() {
        return meters.get(AccountingTemplateStartupSweep.ATTENTION_GAUGE)
                .gauge()
                .value();
    }

    private static AccountingTemplateApplier.Result result(int conflicts) {
        return new AccountingTemplateApplier.Result(
                true,
                Map.of(),
                conflicts == 0
                        ? Map.of(TemplateEntryOutcome.CREATED, 3)
                        : Map.of(TemplateEntryOutcome.CONFLICT, conflicts),
                List.of());
    }
}
