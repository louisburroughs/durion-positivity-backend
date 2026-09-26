package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.entity.BaySpecialtyOperationEntity;
import com.positivity.location.internal.entity.ProcessedEvent;
import com.positivity.location.internal.repository.BaySpecialtyOperationRepository;
import com.positivity.location.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link BaySpecialtyMapProvisioningService}'s idempotency contract (DECISION-LOCATION-025): a
 * tenant with no rows is seeded from the platform template and the map-changed fact publishes; a
 * tenant that already has rows is left alone, but the eventId is still recorded so a redelivery
 * short-circuits immediately.
 */
class BaySpecialtyMapProvisioningServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID TENANT_ID = UUID.fromString("01990000-0000-7000-8000-0000000000b1");
    private static final String EVENT_ID = "01990000-0000-7000-8000-0000000000e1";

    private final BaySpecialtyOperationRepository operationRepository = mock(BaySpecialtyOperationRepository.class);
    private final ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    private final BaySpecialtyMapPublisher publisher = mock(BaySpecialtyMapPublisher.class);

    private BaySpecialtyMapProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new BaySpecialtyMapProvisioningService(
                operationRepository, processedEventRepository, publisher, TEST_CLOCK);
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private List<BaySpecialtyMapProvisioningService.PlatformRow> platformRows() {
        return List.of(
                new BaySpecialtyMapProvisioningService.PlatformRow("ALIGNMENT", "WHEEL-ALIGNMENT-4-WHEEL"),
                new BaySpecialtyMapProvisioningService.PlatformRow("TIRE_SERVICE", "TIRE-INSTALL-SET-4"));
    }

    @Test
    @DisplayName("An empty tenant is seeded from the platform template and the map-changed fact publishes")
    void seedsFromPlatformTemplateWhenEmpty() {
        when(operationRepository.findAll()).thenReturn(List.of());

        service.provisionIfNeeded(TENANT_ID, EVENT_ID, platformRows());

        ArgumentCaptor<BaySpecialtyOperationEntity> saved = ArgumentCaptor.forClass(BaySpecialtyOperationEntity.class);
        verify(operationRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(BaySpecialtyOperationEntity::getBayType, BaySpecialtyOperationEntity::getOperationCode)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ALIGNMENT", "WHEEL-ALIGNMENT-4-WHEEL"),
                        org.assertj.core.groups.Tuple.tuple("TIRE_SERVICE", "TIRE-INSTALL-SET-4"));

        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo(EVENT_ID);
        assertThat(processed.getValue().getOwner()).isEqualTo(BaySpecialtyMapProvisioningService.OWNER);

        verify(publisher).publishChanged(TENANT_ID);
    }

    @Test
    @DisplayName("A tenant that already has map rows is left alone: no copy, no publish, but still marked processed")
    void alreadyProvisionedTenantIsANoOp() {
        when(operationRepository.findAll())
                .thenReturn(List.of(BaySpecialtyOperationEntity.builder()
                        .bayType("ALIGNMENT")
                        .operationCode("WHEEL-ALIGNMENT-4-WHEEL")
                        .build()));

        service.provisionIfNeeded(TENANT_ID, EVENT_ID, platformRows());

        verify(operationRepository, never()).save(any());
        verify(publisher, never()).publishChanged(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("A redelivered eventId already recorded is a complete no-op")
    void redeliveredEventIdIsANoOp() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

        service.provisionIfNeeded(TENANT_ID, EVENT_ID, platformRows());

        verify(operationRepository, never()).findAll();
        verify(operationRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
        verify(publisher, never()).publishChanged(any());
    }

    // -------------------------------------------------------------------------------------------
    // readPlatformTemplate (startup-sweep and listener both read the template through this)
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("readPlatformTemplate reads bay_specialty_operation under the platform tenant's own binding")
    void readPlatformTemplateReadsUnderPlatformTenant() {
        when(operationRepository.findAll()).thenAnswer(invocation -> {
            assertThat(TenantContext.current()).contains(PlatformTenant.ID);
            return List.of(BaySpecialtyOperationEntity.builder()
                    .bayType("ALIGNMENT")
                    .operationCode("WHEEL-ALIGNMENT-4-WHEEL")
                    .build());
        });

        List<BaySpecialtyMapProvisioningService.PlatformRow> rows = service.readPlatformTemplate();

        assertThat(rows)
                .containsExactly(
                        new BaySpecialtyMapProvisioningService.PlatformRow("ALIGNMENT", "WHEEL-ALIGNMENT-4-WHEEL"));
        assertThat(TenantContext.current()).isEmpty();
    }

    // -------------------------------------------------------------------------------------------
    // provisionIfMissing (startup-sweep backfill: no processed_events at all)
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("provisionIfMissing copies the template and publishes for an empty tenant, returning true")
    void provisionIfMissingCopiesWhenEmpty() {
        when(operationRepository.findAll()).thenReturn(List.of());

        boolean backfilled = service.provisionIfMissing(TENANT_ID, platformRows());

        assertThat(backfilled).isTrue();
        ArgumentCaptor<BaySpecialtyOperationEntity> saved = ArgumentCaptor.forClass(BaySpecialtyOperationEntity.class);
        verify(operationRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(BaySpecialtyOperationEntity::getBayType, BaySpecialtyOperationEntity::getOperationCode)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ALIGNMENT", "WHEEL-ALIGNMENT-4-WHEEL"),
                        org.assertj.core.groups.Tuple.tuple("TIRE_SERVICE", "TIRE-INSTALL-SET-4"));
        verify(publisher).publishChanged(TENANT_ID);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("provisionIfMissing leaves a non-empty tenant untouched and returns false")
    void provisionIfMissingLeavesNonEmptyTenantAlone() {
        when(operationRepository.findAll())
                .thenReturn(List.of(BaySpecialtyOperationEntity.builder()
                        .bayType("ALIGNMENT")
                        .operationCode("WHEEL-ALIGNMENT-4-WHEEL")
                        .build()));

        boolean backfilled = service.provisionIfMissing(TENANT_ID, platformRows());

        assertThat(backfilled).isFalse();
        verify(operationRepository, never()).save(any());
        verify(publisher, never()).publishChanged(any());
        verify(processedEventRepository, never()).save(any());
    }
}
