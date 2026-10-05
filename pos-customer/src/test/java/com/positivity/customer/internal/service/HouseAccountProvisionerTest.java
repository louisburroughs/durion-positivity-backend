package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.TenantRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link HouseAccountProvisioner}: create, idempotent, the race on the unique index, and failure
 * isolation per tenant — over a real {@link TenantIterator}, so the tenant binding is the one
 * production gets.
 */
@DisplayName("HouseAccountProvisioner (CAP:550 S7)")
class HouseAccountProvisionerTest {

    private static final UUID TENANT_A = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("01900000-0000-7000-8000-000000000002");
    private static final UUID PARTY_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000c1");

    private final List<UUID> tenants = new ArrayList<>(List.of(TENANT_A, TENANT_B));
    private final TenantRegistry registry = () -> List.copyOf(tenants);
    private final HouseAccountProvisioningService provisioningService = mock(HouseAccountProvisioningService.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();

    private HouseAccountProvisioner provisioner() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(meters);
        return new HouseAccountProvisioner(new TenantIterator(registry), provisioningService, meterProvider);
    }

    private double count(String outcome) {
        var counter = meters.find(HouseAccountProvisioner.METRIC)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("creates the account for every active tenant, each with its own tenant bound")
    void createsForEveryTenant() {
        List<UUID> boundTenants = new CopyOnWriteArrayList<>();
        when(provisioningService.createCashAccountIfMissing()).thenAnswer(invocation -> {
            boundTenants.add(TenantContext.current().orElseThrow());
            return Optional.of(PARTY_ID);
        });

        provisioner().sweep();

        assertThat(boundTenants).containsExactly(TENANT_A, TENANT_B);
        assertThat(count("created")).isEqualTo(2);
        assertThat(count("existing")).isZero();
        assertThat(count("failed")).isZero();
    }

    @Test
    @DisplayName("a second sweep over provisioned tenants creates nothing")
    void isIdempotent() {
        when(provisioningService.createCashAccountIfMissing())
                .thenReturn(Optional.of(PARTY_ID), Optional.of(PARTY_ID), Optional.empty(), Optional.empty());
        HouseAccountProvisioner provisioner = provisioner();

        provisioner.sweep();
        provisioner.sweep();

        assertThat(count("created")).isEqualTo(2);
        assertThat(count("existing")).isEqualTo(2);
    }

    @Test
    @DisplayName("a tenant added to the registry after startup is provisioned by the next sweep")
    void picksUpATenantAddedLater() {
        tenants.remove(TENANT_B);
        when(provisioningService.createCashAccountIfMissing())
                .thenReturn(Optional.of(PARTY_ID), Optional.empty(), Optional.of(PARTY_ID));
        HouseAccountProvisioner provisioner = provisioner();
        provisioner.run(new DefaultApplicationArguments());
        assertThat(count("created")).isEqualTo(1);

        tenants.add(TENANT_B);
        provisioner.sweep();

        verify(provisioningService, times(3)).createCashAccountIfMissing();
        assertThat(count("created")).isEqualTo(2);
        assertThat(count("existing")).isEqualTo(1);
    }

    @Test
    @DisplayName("losing the race on the unique index counts as already provisioned, not as a failure")
    void lostRaceIsTreatedAsExisting() {
        when(provisioningService.createCashAccountIfMissing())
                .thenThrow(new DataIntegrityViolationException("commercial_party_house_account_uk"));
        when(provisioningService.cashAccountExists()).thenReturn(true);

        assertThat(provisioner().provisionTenant(TENANT_A)).isEqualTo(HouseAccountProvisioner.Outcome.EXISTING);

        assertThat(count("existing")).isEqualTo(1);
        assertThat(count("failed")).isZero();
    }

    @Test
    @DisplayName("a refused insert with no account afterwards is a failure, retried on the next sweep")
    void refusedInsertWithoutAnAccountFails() {
        when(provisioningService.createCashAccountIfMissing())
                .thenThrow(new DataIntegrityViolationException("commercial_party_customer_number_key"));
        when(provisioningService.cashAccountExists()).thenReturn(false);

        assertThat(provisioner().provisionTenant(TENANT_A)).isEqualTo(HouseAccountProvisioner.Outcome.FAILED);

        assertThat(count("failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("a party already numbered CASH blocks provisioning: ERROR naming the tenant and that party")
    void customerNumberCollisionIsReportedAtError() {
        UUID holder = UUID.fromString("01980a58-0000-7000-8000-0000000000c9");
        when(provisioningService.createCashAccountIfMissing())
                .thenThrow(new DataIntegrityViolationException("commercial_party_customer_number_key"));
        when(provisioningService.cashAccountExists()).thenReturn(false);
        when(provisioningService.findCashCustomerNumberHolder()).thenReturn(Optional.of(holder));

        try (LogCapture logs = LogCapture.of(HouseAccountProvisioner.class)) {
            assertThat(provisioner().provisionTenant(TENANT_A)).isEqualTo(HouseAccountProvisioner.Outcome.FAILED);

            // Not a transient failure the next sweep cures: an operator has to renumber that party.
            assertThat(logs.messagesAt(Level.ERROR))
                    .singleElement()
                    .asString()
                    .contains(TENANT_A.toString())
                    .contains(holder.toString())
                    .contains("CASH");
        }
        assertThat(count("failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("one tenant's failure does not stop the others")
    void failureIsIsolatedPerTenant() {
        when(provisioningService.createCashAccountIfMissing())
                .thenThrow(new IllegalStateException("tenant A's transaction failed"))
                .thenReturn(Optional.of(PARTY_ID));

        provisioner().sweep();

        verify(provisioningService, times(2)).createCashAccountIfMissing();
        assertThat(count("failed")).isEqualTo(1);
        assertThat(count("created")).isEqualTo(1);
    }

    @Test
    @DisplayName("an incomplete tenant registry is reported at WARN; the tenants it does list are provisioned")
    void incompleteRegistryIsReported() {
        TenantRegistry incomplete = new TenantRegistry() {
            @Override
            public List<UUID> activeTenantIds() {
                return List.of(TENANT_A);
            }

            @Override
            public Snapshot snapshot() {
                return new Snapshot(activeTenantIds(), false);
            }
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(meters);
        when(provisioningService.createCashAccountIfMissing()).thenReturn(Optional.of(PARTY_ID));
        HouseAccountProvisioner provisioner =
                new HouseAccountProvisioner(new TenantIterator(incomplete), provisioningService, meterProvider);

        try (LogCapture logs = LogCapture.of(HouseAccountProvisioner.class)) {
            provisioner.sweep();

            assertThat(count("created")).isEqualTo(1);
            assertThat(logs.messagesAt(Level.WARN))
                    .singleElement()
                    .asString()
                    .contains("incomplete")
                    .contains("next sweep");
        }
    }

    @Test
    @DisplayName("a complete tenant registry raises no warning")
    void completeRegistryIsQuiet() {
        when(provisioningService.createCashAccountIfMissing()).thenReturn(Optional.of(PARTY_ID));

        try (LogCapture logs = LogCapture.of(HouseAccountProvisioner.class)) {
            provisioner().sweep();

            assertThat(logs.messagesAt(Level.WARN)).isEmpty();
        }
    }

    @Test
    @DisplayName("startup never blocks: a failing sweep is swallowed")
    void startupSwallowsFailures() {
        TenantRegistry broken = () -> {
            throw new IllegalStateException("registry unavailable");
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> noMeters = mock(ObjectProvider.class);
        HouseAccountProvisioner provisioner =
                new HouseAccountProvisioner(new TenantIterator(broken), provisioningService, noMeters);

        assertThatCode(() -> provisioner.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("provisions without a metrics registry")
    void worksWithoutMetrics() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> noMeters = mock(ObjectProvider.class);
        when(provisioningService.createCashAccountIfMissing()).thenReturn(Optional.of(PARTY_ID));
        HouseAccountProvisioner provisioner =
                new HouseAccountProvisioner(new TenantIterator(registry), provisioningService, noMeters);

        assertThat(provisioner.provisionTenant(TENANT_A)).isEqualTo(HouseAccountProvisioner.Outcome.CREATED);
    }
}
