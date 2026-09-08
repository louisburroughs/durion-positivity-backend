package com.positivity.inventory.internal.replenishment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.purchasesuggestion.PurchaseSuggestionResponse;
import com.positivity.inventory.internal.entity.PurchaseSuggestion;
import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.inventory.internal.repository.PurchaseSuggestionRepository;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.internal.service.ForecastSiteResolver;
import com.positivity.inventory.internal.service.LocationScopeService;
import com.positivity.inventory.internal.service.PurchaseOrderCommandPublisher;
import com.positivity.security.common.LocationScopeDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Location scope on the purchase-suggestion reads (ADR-0061 §3, #1872). */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseSuggestionServiceImpl location scope")
class PurchaseSuggestionServiceImplTest {

    private static final UUID SITE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SUGGESTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private PurchaseSuggestionRepository purchaseSuggestionRepository;

    @Mock
    private ExtProductUomReplicaRepository extProductUomReplicaRepository;

    @Mock
    private PurchaseOrderCommandPublisher purchaseOrderCommandPublisher;

    @Mock
    private ForecastSiteResolver forecastSiteResolver;

    @Mock
    private LocationScopeService locationScopeService;

    private PurchaseSuggestionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseSuggestionServiceImpl(
                purchaseSuggestionRepository,
                extProductUomReplicaRepository,
                purchaseOrderCommandPublisher,
                forecastSiteResolver,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                locationScopeService);
    }

    private static PurchaseSuggestion suggestion() {
        return PurchaseSuggestion.builder()
                .suggestionId(SUGGESTION_ID)
                .policyId(UUID.randomUUID())
                .itemSKU("SKU-1")
                .locationId(SITE)
                .suggestedQuantity(3)
                .selectionReason("test")
                .status(PurchaseSuggestionStatus.SUGGESTED)
                .build();
    }

    @Test
    @DisplayName("no location filter: a scoped caller's page comes from the within-reach query")
    void list_scopedNoFilter_queriesWithinReach() {
        when(locationScopeService.narrowTo(isNull(), eq(InventoryPermissionRegistry.INVENTORY_VIEW)))
                .thenReturn(Optional.of(Set.of(SITE)));
        when(purchaseSuggestionRepository.findWithinLocations(Set.of(SITE))).thenReturn(List.of(suggestion()));

        Page<PurchaseSuggestionResponse> page = service.listPurchaseSuggestions(null, null, null, Pageable.unpaged());

        assertThat(page.getContent())
                .extracting(PurchaseSuggestionResponse::getSuggestionId)
                .containsExactly(SUGGESTION_ID);
        verify(purchaseSuggestionRepository, never()).findAll();
    }

    @Test
    @DisplayName("empty reach: an empty page without querying")
    void list_scopedEmptyReach_emptyWithoutQuery() {
        when(locationScopeService.narrowTo(isNull(), eq(InventoryPermissionRegistry.INVENTORY_VIEW)))
                .thenReturn(Optional.of(Set.of()));

        assertThat(service.listPurchaseSuggestions(null, null, null, Pageable.unpaged()))
                .isEmpty();
        verifyNoInteractions(purchaseSuggestionRepository);
    }

    @Test
    @DisplayName("location filter: gated, then the full set is filtered as before")
    void list_filter_isGatedThenFiltered() {
        when(purchaseSuggestionRepository.findAll()).thenReturn(List.of(suggestion()));

        Page<PurchaseSuggestionResponse> page = service.listPurchaseSuggestions(null, null, SITE, Pageable.unpaged());

        assertThat(page.getContent()).hasSize(1);
        verify(locationScopeService).narrowTo(SITE, InventoryPermissionRegistry.INVENTORY_VIEW);
    }

    @Test
    @DisplayName("getPurchaseSuggestion gates on the loaded suggestion's destination, after the 404")
    void get_gatesAfterLoad() {
        when(purchaseSuggestionRepository.findById(SUGGESTION_ID)).thenReturn(Optional.of(suggestion()));
        doThrow(new LocationScopeDeniedException(InventoryPermissionRegistry.INVENTORY_VIEW, SITE.toString()))
                .when(locationScopeService)
                .require(SITE, InventoryPermissionRegistry.INVENTORY_VIEW);

        assertThatThrownBy(() -> service.getPurchaseSuggestion(SUGGESTION_ID))
                .isInstanceOf(LocationScopeDeniedException.class);
    }

    @Test
    @DisplayName("getPurchaseSuggestion: unknown id is 404 before any scope check")
    void get_unknownBeforeScope() {
        when(purchaseSuggestionRepository.findById(SUGGESTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPurchaseSuggestion(SUGGESTION_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(locationScopeService);
    }
}
