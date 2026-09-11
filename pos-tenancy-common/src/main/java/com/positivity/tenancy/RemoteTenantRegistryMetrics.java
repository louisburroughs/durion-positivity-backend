package com.positivity.tenancy;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Micrometer gauges over a {@link RemoteTenantRegistry}, bound once every singleton exists so the
 * binding never depends on whether the {@code MeterRegistry} was created before or after the
 * tenancy beans. Absent when Micrometer is not on the classpath or the registry is not remote.
 *
 * <ul>
 *   <li>{@code tenancy.registry.tenants}: tenants in the current snapshot
 *   <li>{@code tenancy.registry.last_success_epoch_seconds}: when the snapshot was last fetched
 *       ({@code 0} while still on the static list); alert on its age
 * </ul>
 */
public class RemoteTenantRegistryMetrics implements SmartInitializingSingleton {

    static final String TENANTS = "tenancy.registry.tenants";
    static final String LAST_SUCCESS = "tenancy.registry.last_success_epoch_seconds";

    private final ObjectProvider<RemoteTenantRegistry> registry;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public RemoteTenantRegistryMetrics(
            @NonNull ObjectProvider<RemoteTenantRegistry> registry,
            @NonNull ObjectProvider<MeterRegistry> meterRegistry) {
        this.registry = registry;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        RemoteTenantRegistry remote = registry.getIfUnique();
        MeterRegistry meters = meterRegistry.getIfUnique();
        if (remote == null || meters == null) {
            return;
        }
        bind(remote, meters);
    }

    static void bind(@NonNull RemoteTenantRegistry remote, @NonNull MeterRegistry meters) {
        Gauge.builder(TENANTS, remote, RemoteTenantRegistry::snapshotSize)
                .description("Tenants in the current RemoteTenantRegistry snapshot")
                .register(meters);
        Gauge.builder(LAST_SUCCESS, remote, RemoteTenantRegistry::lastSuccessEpochSeconds)
                .description("Epoch seconds of the last successful tenant registry fetch; 0 until the first")
                .register(meters);
    }
}
