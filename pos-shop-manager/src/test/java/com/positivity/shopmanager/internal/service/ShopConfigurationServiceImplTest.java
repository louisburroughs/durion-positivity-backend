package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.dto.ShopResponse;
import com.positivity.shopmanager.internal.dto.ShopUpsertRequest;
import com.positivity.shopmanager.internal.entity.Shop;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The shop row is the gate on every location-parameterised read in this module — {@code
 * getScheduleView} answers 404 for an appointment-free day when {@code existsById} is false — and
 * until this service existed nothing in {@code src/main} ever wrote one. These cases pin the two
 * things a seed pipeline depends on: the id is the location's, and a re-run converges.
 */
@ExtendWith(MockitoExtension.class)
class ShopConfigurationServiceImplTest {

    private static final UUID LOCATION_ID = UUID.fromString("01a0a459-65b0-7609-b8a2-58332d3af186");

    @Mock
    private ShopRepository shopRepository;

    @InjectMocks
    private ShopConfigurationServiceImpl service;

    private ShopUpsertRequest request;

    @BeforeEach
    void setUp() {
        request = ShopUpsertRequest.builder()
                .name("Charlotte Main Service Center")
                .address("100 Trade Street")
                .timezone("America/New_York")
                .build();
    }

    @Test
    @DisplayName("creates the shop under the location's own id, which is what the schedule view resolves")
    void createsShopKeyedOnLocationId() {
        when(shopRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());
        when(shopRepository.save(any(Shop.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShopResponse response = service.upsert(LOCATION_ID, request);

        ArgumentCaptor<Shop> saved = ArgumentCaptor.forClass(Shop.class);
        verify(shopRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(LOCATION_ID);
        assertThat(saved.getValue().getName()).isEqualTo("Charlotte Main Service Center");
        assertThat(saved.getValue().getTimezone()).isEqualTo("America/New_York");
        assertThat(response.getId()).isEqualTo(LOCATION_ID);
    }

    @Test
    @DisplayName("replaces an existing shop rather than creating a second, so a reseed converges")
    void replacesExistingShop() {
        Shop existing =
                Shop.builder().id(LOCATION_ID).name("Old name").timezone("UTC").build();
        when(shopRepository.findById(LOCATION_ID)).thenReturn(Optional.of(existing));
        when(shopRepository.save(any(Shop.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.upsert(LOCATION_ID, request);

        ArgumentCaptor<Shop> saved = ArgumentCaptor.forClass(Shop.class);
        verify(shopRepository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(existing);
        assertThat(saved.getValue().getId()).isEqualTo(LOCATION_ID);
        assertThat(saved.getValue().getName()).isEqualTo("Charlotte Main Service Center");
    }

    @Test
    @DisplayName("a blank timezone is left unset, so the schedule view's documented UTC fallback applies")
    void blankTimezoneIsLeftUnset() {
        when(shopRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());
        when(shopRepository.save(any(Shop.class))).thenAnswer(invocation -> invocation.getArgument(0));
        request.setTimezone("   ");

        service.upsert(LOCATION_ID, request);

        ArgumentCaptor<Shop> saved = ArgumentCaptor.forClass(Shop.class);
        verify(shopRepository).save(saved.capture());
        assertThat(saved.getValue().getTimezone()).isNull();
    }

    @Test
    @DisplayName("a timezone that is not a zone id is refused, not stored")
    void invalidTimezoneIsRefused() {
        // Stored blind it would silently shift every appointment on the board rather than fail.
        request.setTimezone("America/Charlotte");

        assertThatThrownBy(() -> service.upsert(LOCATION_ID, request))
                .isInstanceOf(ShopManagerValidationException.class)
                .hasMessageContaining("America/Charlotte");

        verify(shopRepository, never()).save(any(Shop.class));
    }
}
