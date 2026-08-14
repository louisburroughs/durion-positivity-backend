package com.positivity.supplier.internal.pricecatalog.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.repository.PriceCatalogImportRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.service.SupplierScheduleCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Scheduled PRICAT imports (#1224)")
class PriceCatalogSchedulerTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID BINDING_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-13T09:00:00Z"), ZoneOffset.UTC);

    @Mock
    private SupplierEndpointBindingRepository bindingRepository;

    @Mock
    private SupplierProfileRepository profileRepository;

    @Mock
    private PriceCatalogImportRepository importRepository;

    @Mock
    private SupplierScheduleCoordinator scheduleCoordinator;

    @Mock
    private PriceCatalogImporter importService;

    private PriceCatalogScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PriceCatalogScheduler(
                bindingRepository, profileRepository, importRepository, scheduleCoordinator, importService, CLOCK);

        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef("michelin-eu");
        profile.setEnabled(true);
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));

        when(bindingRepository.findByCapabilityAndEnabledTrueAndScheduleCronIsNotNull(SupplierCapability.PRICE_CATALOG))
                .thenReturn(List.of(binding("0 0 3 * * *")));
        when(scheduleCoordinator.tryClaim(eq(BINDING_ID), anyString())).thenReturn(true);

        PriceCatalogImportEntity result = PriceCatalogImportEntity.builder()
                .importManifestId(UUID.randomUUID())
                .vendorProfileId(PROFILE_ID)
                .status(PriceCatalogImportStatus.COMPLETED)
                .build();
        when(importService.runImport(any(SupplierRef.class))).thenReturn(result);
    }

    private static SupplierEndpointBindingEntity binding(String cron) {
        SupplierEndpointBindingEntity binding = new SupplierEndpointBindingEntity();
        binding.setId(BINDING_ID);
        binding.setVendorProfileId(PROFILE_ID);
        binding.setCapability(SupplierCapability.PRICE_CATALOG);
        binding.setScheduleCron(cron);
        binding.setEnabled(true);
        return binding;
    }

    private void lastImportAt(Instant fetchedAt) {
        Page<PriceCatalogImportEntity> page = new PageImpl<>(List.of(PriceCatalogImportEntity.builder()
                .importManifestId(UUID.randomUUID())
                .vendorProfileId(PROFILE_ID)
                .fetchedAt(fetchedAt)
                .status(PriceCatalogImportStatus.COMPLETED)
                .build()));
        when(importRepository.findByVendorProfileIdOrderByFetchedAtDesc(eq(PROFILE_ID), any(Pageable.class)))
                .thenReturn(page);
    }

    @Test
    void runsImmediatelyWhenTheProfileHasNeverImported() {
        when(importRepository.findByVendorProfileIdOrderByFetchedAtDesc(eq(PROFILE_ID), any(Pageable.class)))
                .thenReturn(Page.empty());

        scheduler.runDueImports();

        verify(importService).runImport(new SupplierRef("michelin-eu"));
    }

    @Test
    void runsWhenTheCronFireTimeAfterTheLastImportHasPassed() {
        lastImportAt(Instant.parse("2026-08-12T03:00:00Z"));

        scheduler.runDueImports();

        verify(importService).runImport(new SupplierRef("michelin-eu"));
    }

    @Test
    void doesNotRunBeforeTheNextCronFireTime() {
        lastImportAt(Instant.parse("2026-08-13T03:00:00Z"));

        scheduler.runDueImports();

        verify(importService, never()).runImport(any());
    }

    @Test
    void skipsTheRunWhenAnotherInstanceHoldsTheLease() {
        when(importRepository.findByVendorProfileIdOrderByFetchedAtDesc(eq(PROFILE_ID), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(scheduleCoordinator.tryClaim(eq(BINDING_ID), anyString())).thenReturn(false);

        scheduler.runDueImports();

        verify(importService, never()).runImport(any());
    }

    @Test
    void releasesTheLeaseWithTheRunOutcome() {
        when(importRepository.findByVendorProfileIdOrderByFetchedAtDesc(eq(PROFILE_ID), any(Pageable.class)))
                .thenReturn(Page.empty());

        scheduler.runDueImports();

        verify(scheduleCoordinator).release(eq(BINDING_ID), anyString(), eq("COMPLETED"));
    }

    @Test
    void releasesTheLeaseEvenWhenTheImportThrows() {
        when(importRepository.findByVendorProfileIdOrderByFetchedAtDesc(eq(PROFILE_ID), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(importService.runImport(any(SupplierRef.class))).thenThrow(new IllegalStateException("boom"));

        scheduler.runDueImports();

        verify(scheduleCoordinator).release(eq(BINDING_ID), anyString(), eq("FAILED"));
    }

    @Test
    void skipsADisabledProfileWithoutClaimingItsLease() {
        SupplierProfileEntity disabled = new SupplierProfileEntity();
        disabled.setVendorProfileId(PROFILE_ID);
        disabled.setSupplierRef("michelin-eu");
        disabled.setEnabled(false);
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(disabled));

        scheduler.runDueImports();

        verify(scheduleCoordinator, never()).tryClaim(any(), anyString());
    }

    @Test
    void neverFiresOnAnUnparseableCronRatherThanFiringEveryTick() {
        when(bindingRepository.findByCapabilityAndEnabledTrueAndScheduleCronIsNotNull(SupplierCapability.PRICE_CATALOG))
                .thenReturn(List.of(binding("not a cron")));

        scheduler.runDueImports();

        verify(importService, never()).runImport(any());
    }

    @Test
    void oneFailingBindingDoesNotStopTheOthers() {
        SupplierEndpointBindingEntity broken = binding("0 0 3 * * *");
        broken.setVendorProfileId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4aff"));
        when(bindingRepository.findByCapabilityAndEnabledTrueAndScheduleCronIsNotNull(SupplierCapability.PRICE_CATALOG))
                .thenReturn(List.of(broken, binding("0 0 3 * * *")));
        when(profileRepository.findById(broken.getVendorProfileId())).thenThrow(new IllegalStateException("db down"));
        when(importRepository.findByVendorProfileIdOrderByFetchedAtDesc(eq(PROFILE_ID), any(Pageable.class)))
                .thenReturn(Page.empty());

        scheduler.runDueImports();

        verify(importService).runImport(new SupplierRef("michelin-eu"));
    }
}
