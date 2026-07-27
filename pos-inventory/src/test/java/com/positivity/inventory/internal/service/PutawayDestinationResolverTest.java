package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.ValidationResult;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.PutawayDestinationStrategy;
import com.positivity.inventory.internal.enums.PutawayFallbackReason;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import com.positivity.inventory.internal.exception.LocationAtCapacityException;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.PutawayTaskRepository;
import com.positivity.inventory.internal.service.PutawayDestinationResolver.ResolvedDestination;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingCandidate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PutawayDestinationResolverTest {

    private static final UUID ANCHOR = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    private static final UUID NEAR = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID FAR = UUID.fromString("00000000-0000-0000-0000-0000000000c0");
    private static final UUID SITE = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
    private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LAST_USED_BIN = UUID.fromString("00000000-0000-0000-0000-0000000000d0");
    private static final int QTY = 5;

    @Mock
    private PutawayTaskRepository putawayTaskRepository;

    @Mock
    private ExtStorageLocationReplicaRepository extStorageLocationReplicaRepository;

    @Mock
    private ProximitySourcingStrategy proximitySourcingStrategy;

    @Mock
    private com.positivity.inventory.service.PutawayValidationService putawayValidationService;

    private PutawayDestinationResolver resolver() {
        return new PutawayDestinationResolver(
                putawayTaskRepository,
                extStorageLocationReplicaRepository,
                proximitySourcingStrategy,
                putawayValidationService);
    }

    private PutawayRule rule(PutawayDestinationStrategy strategy) {
        return PutawayRule.builder()
                .priority(1)
                .destinationLocationId(ANCHOR)
                .destinationStrategy(strategy)
                .build();
    }

    // ─── FIXED ──────────────────────────────────────────────────────────────

    @Test
    void fixed_returnsRuleDestination_withNoFallback_andNoLookups() {
        ResolvedDestination result = resolver().resolve(rule(PutawayDestinationStrategy.FIXED), PRODUCT, QTY);

        assertThat(result.destinationLocationId()).isEqualTo(ANCHOR);
        assertThat(result.isFallback()).isFalse();
        assertThat(result.fallbackReason()).isNull();
        assertThat(result.originalSuggestedLocationId()).isNull();
        verifyNoInteractions(
                putawayTaskRepository,
                extStorageLocationReplicaRepository,
                proximitySourcingStrategy,
                putawayValidationService);
    }

    @Test
    void nullStrategy_defaultsToFixed() {
        PutawayRule rule =
                PutawayRule.builder().priority(1).destinationLocationId(ANCHOR).build();
        rule.setDestinationStrategy(null);

        ResolvedDestination result = resolver().resolve(rule, PRODUCT, QTY);

        assertThat(result.destinationLocationId()).isEqualTo(ANCHOR);
        assertThat(result.isFallback()).isFalse();
    }

    // ─── LAST_USED ──────────────────────────────────────────────────────────

    @Test
    void lastUsed_picksMostRecentlyUsedBinForSku() {
        PutawayTask completed = PutawayTask.builder()
                .actualDestinationLocationId(LAST_USED_BIN)
                .status(PutawayTaskStatus.COMPLETED)
                .build();
        when(putawayTaskRepository
                        .findFirstByProductIdAndStatusAndActualDestinationLocationIdIsNotNullOrderByUpdatedAtDesc(
                                PRODUCT, PutawayTaskStatus.COMPLETED))
                .thenReturn(Optional.of(completed));

        ResolvedDestination result = resolver().resolve(rule(PutawayDestinationStrategy.LAST_USED), PRODUCT, QTY);

        assertThat(result.destinationLocationId()).isEqualTo(LAST_USED_BIN);
        assertThat(result.isFallback()).isFalse();
        assertThat(result.fallbackReason()).isNull();
    }

    @Test
    void lastUsed_neverUsed_fallsBackToFixedDestination_withUnavailableReason() {
        when(putawayTaskRepository
                        .findFirstByProductIdAndStatusAndActualDestinationLocationIdIsNotNullOrderByUpdatedAtDesc(
                                PRODUCT, PutawayTaskStatus.COMPLETED))
                .thenReturn(Optional.empty());

        ResolvedDestination result = resolver().resolve(rule(PutawayDestinationStrategy.LAST_USED), PRODUCT, QTY);

        assertThat(result.destinationLocationId()).isEqualTo(ANCHOR);
        assertThat(result.isFallback()).isTrue();
        assertThat(result.fallbackReason()).isEqualTo(PutawayFallbackReason.UNAVAILABLE);
    }

    // ─── CLOSEST_AVAILABLE ──────────────────────────────────────────────────

    private void stubSiteWithBins() {
        when(extStorageLocationReplicaRepository.findById(ANCHOR)).thenReturn(Optional.of(bin(ANCHOR)));
        when(extStorageLocationReplicaRepository.findBySiteId(SITE))
                .thenReturn(List.of(bin(ANCHOR), bin(NEAR), bin(FAR)));
    }

    @Test
    void closestAvailable_picksNearestBinWithRoom() {
        stubSiteWithBins();
        when(proximitySourcingStrategy.order(any()))
                .thenReturn(List.of(candidate(NEAR), candidate(FAR), candidate(ANCHOR)));
        // NEAR has room.
        when(putawayValidationService.validateLocationCapacity(NEAR, QTY)).thenReturn(ValidationResult.success());

        ResolvedDestination result =
                resolver().resolve(rule(PutawayDestinationStrategy.CLOSEST_AVAILABLE), PRODUCT, QTY);

        assertThat(result.destinationLocationId()).isEqualTo(NEAR);
        assertThat(result.isFallback()).isFalse();
        assertThat(result.fallbackReason()).isNull();
        assertThat(result.originalSuggestedLocationId()).isNull();
        // Nearest had room — no need to evaluate farther bins.
        Mockito.verify(putawayValidationService, never()).validateLocationCapacity(eq(FAR), any(int.class));
    }

    @Test
    void closestAvailable_skipsFullNearestBin_fallsThroughToNextNearest() {
        stubSiteWithBins();
        when(proximitySourcingStrategy.order(any()))
                .thenReturn(List.of(candidate(NEAR), candidate(FAR), candidate(ANCHOR)));
        when(putawayValidationService.validateLocationCapacity(NEAR, QTY))
                .thenThrow(new LocationAtCapacityException(NEAR, 10, 10));
        when(putawayValidationService.validateLocationCapacity(FAR, QTY)).thenReturn(ValidationResult.success());

        ResolvedDestination result =
                resolver().resolve(rule(PutawayDestinationStrategy.CLOSEST_AVAILABLE), PRODUCT, QTY);

        assertThat(result.destinationLocationId()).isEqualTo(FAR);
        assertThat(result.isFallback()).isTrue();
        assertThat(result.fallbackReason()).isEqualTo(PutawayFallbackReason.DESTINATION_FULL);
        assertThat(result.originalSuggestedLocationId()).isEqualTo(NEAR);
    }

    @Test
    void closestAvailable_allBinsFull_fallsBackToFixedDestination() {
        stubSiteWithBins();
        when(proximitySourcingStrategy.order(any()))
                .thenReturn(List.of(candidate(NEAR), candidate(FAR), candidate(ANCHOR)));
        when(putawayValidationService.validateLocationCapacity(any(UUID.class), eq(QTY)))
                .thenThrow(new LocationAtCapacityException(NEAR, 10, 10));

        ResolvedDestination result =
                resolver().resolve(rule(PutawayDestinationStrategy.CLOSEST_AVAILABLE), PRODUCT, QTY);

        assertThat(result.destinationLocationId()).isEqualTo(ANCHOR);
        assertThat(result.isFallback()).isTrue();
        assertThat(result.fallbackReason()).isEqualTo(PutawayFallbackReason.DESTINATION_FULL);
        assertThat(result.originalSuggestedLocationId()).isEqualTo(NEAR);
    }

    @Test
    void closestAvailable_unknownAnchorSite_degradesToFixed() {
        when(extStorageLocationReplicaRepository.findById(ANCHOR)).thenReturn(Optional.empty());

        ResolvedDestination result =
                resolver().resolve(rule(PutawayDestinationStrategy.CLOSEST_AVAILABLE), PRODUCT, QTY);

        assertThat(result.destinationLocationId()).isEqualTo(ANCHOR);
        assertThat(result.isFallback()).isFalse();
        verifyNoInteractions(proximitySourcingStrategy, putawayValidationService);
    }

    @Test
    void closestAvailable_noActiveCandidateBins_degradesToFixed() {
        when(extStorageLocationReplicaRepository.findById(ANCHOR)).thenReturn(Optional.of(bin(ANCHOR)));
        when(extStorageLocationReplicaRepository.findBySiteId(SITE)).thenReturn(List.of(inactiveBin(NEAR)));

        ResolvedDestination result =
                resolver().resolve(rule(PutawayDestinationStrategy.CLOSEST_AVAILABLE), PRODUCT, QTY);

        assertThat(result.destinationLocationId()).isEqualTo(ANCHOR);
        assertThat(result.isFallback()).isFalse();
        verifyNoInteractions(proximitySourcingStrategy, putawayValidationService);
    }

    private ExtStorageLocationReplica bin(UUID id) {
        return ExtStorageLocationReplica.builder()
                .storageLocationId(id)
                .siteId(SITE)
                .status("ACTIVE")
                .maxUnitCapacity(100)
                .aggregateVersion(1L)
                .build();
    }

    private ExtStorageLocationReplica inactiveBin(UUID id) {
        return ExtStorageLocationReplica.builder()
                .storageLocationId(id)
                .siteId(SITE)
                .status("INACTIVE")
                .maxUnitCapacity(100)
                .aggregateVersion(1L)
                .build();
    }

    private SourcingCandidate candidate(UUID id) {
        return new SourcingCandidate(id, null);
    }
}
