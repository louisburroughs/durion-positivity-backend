package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.warranty.internal.dto.ProviderRequest;
import com.positivity.warranty.internal.dto.ProviderResponse;
import com.positivity.warranty.internal.entity.WarrantyProvider;
import com.positivity.warranty.internal.enums.ProviderType;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.repository.WarrantyProviderRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Warranty provider CRUD (PRD §3.1): field mapping, ACTIVE default, list filter branches. */
@ExtendWith(MockitoExtension.class)
class ProviderServiceImplTest {

    private static final UUID PROVIDER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000101");
    private static final UUID AP_VENDOR_ID = UUID.fromString("018f0000-0000-7000-8000-000000000102");
    private static final UUID MANUFACTURER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000103");

    @Mock
    private WarrantyProviderRepository providerRepository;

    @InjectMocks
    private ProviderServiceImpl service;

    private static ProviderRequest request(String status) {
        return new ProviderRequest(
                "Michelin North America",
                ProviderType.MANUFACTURER,
                status,
                AP_VENDOR_ID,
                MANUFACTURER_ID,
                "Portal",
                "https://portal.example.com",
                "Jane Doe",
                "555-0100",
                "claims@example.com");
    }

    private static WarrantyProvider existingProvider(String status) {
        return WarrantyProvider.builder()
                .id(PROVIDER_ID)
                .name("Old Name")
                .status(status)
                .providerType(ProviderType.DEALER)
                .version(3L)
                .build();
    }

    private void stubSaveEcho() {
        when(providerRepository.save(any(WarrantyProvider.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    class Create {

        @Test
        void mapsAllFieldsAndKeepsExplicitStatus() {
            stubSaveEcho();

            ProviderResponse response = service.create(request(WarrantyProvider.STATUS_INACTIVE));

            assertThat(response.name()).isEqualTo("Michelin North America");
            assertThat(response.providerType()).isEqualTo(ProviderType.MANUFACTURER);
            assertThat(response.status()).isEqualTo(WarrantyProvider.STATUS_INACTIVE);
            assertThat(response.apVendorId()).isEqualTo(AP_VENDOR_ID);
            assertThat(response.manufacturerId()).isEqualTo(MANUFACTURER_ID);
            assertThat(response.claimSubmissionMethod()).isEqualTo("Portal");
            assertThat(response.portalUrl()).isEqualTo("https://portal.example.com");
            assertThat(response.contactName()).isEqualTo("Jane Doe");
            assertThat(response.contactPhone()).isEqualTo("555-0100");
            assertThat(response.contactEmail()).isEqualTo("claims@example.com");
        }

        @Test
        void defaultsStatusToActiveWhenAbsent() {
            stubSaveEcho();

            ProviderResponse response = service.create(request(null));

            assertThat(response.status()).isEqualTo(WarrantyProvider.STATUS_ACTIVE);
        }
    }

    @Nested
    class Update {

        @Test
        void overwritesFieldsOfExistingProvider() {
            when(providerRepository.findById(PROVIDER_ID))
                    .thenReturn(Optional.of(existingProvider(WarrantyProvider.STATUS_ACTIVE)));
            stubSaveEcho();

            ProviderResponse response = service.update(PROVIDER_ID, request(WarrantyProvider.STATUS_INACTIVE));

            assertThat(response.id()).isEqualTo(PROVIDER_ID);
            assertThat(response.name()).isEqualTo("Michelin North America");
            assertThat(response.providerType()).isEqualTo(ProviderType.MANUFACTURER);
            assertThat(response.status()).isEqualTo(WarrantyProvider.STATUS_INACTIVE);
        }

        @Test
        void nullStatusInRequestKeepsExistingStatus() {
            when(providerRepository.findById(PROVIDER_ID))
                    .thenReturn(Optional.of(existingProvider(WarrantyProvider.STATUS_INACTIVE)));
            stubSaveEcho();

            ProviderResponse response = service.update(PROVIDER_ID, request(null));

            assertThat(response.status()).isEqualTo(WarrantyProvider.STATUS_INACTIVE);
        }

        @Test
        void missingProviderIs404WithCode() {
            when(providerRepository.findById(PROVIDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(PROVIDER_ID, request(null)))
                    .isInstanceOf(WarrantyNotFoundException.class)
                    .satisfies(ex -> assertThat(((WarrantyNotFoundException) ex).getCode())
                            .isEqualTo(ProviderServiceImpl.NOT_FOUND_CODE));
            verify(providerRepository, never()).save(any());
        }
    }

    @Nested
    class GetById {

        @Test
        void mapsEntity() {
            when(providerRepository.findById(PROVIDER_ID))
                    .thenReturn(Optional.of(existingProvider(WarrantyProvider.STATUS_ACTIVE)));

            ProviderResponse response = service.getById(PROVIDER_ID);

            assertThat(response.id()).isEqualTo(PROVIDER_ID);
            assertThat(response.name()).isEqualTo("Old Name");
            assertThat(response.version()).isEqualTo(3L);
        }

        @Test
        void missingProviderIs404() {
            when(providerRepository.findById(PROVIDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(PROVIDER_ID))
                    .isInstanceOf(WarrantyNotFoundException.class)
                    .satisfies(ex -> assertThat(((WarrantyNotFoundException) ex).getCode())
                            .isEqualTo(ProviderServiceImpl.NOT_FOUND_CODE));
        }
    }

    @Nested
    class ListFilters {

        private static WarrantyProvider provider(String status, ProviderType type) {
            return WarrantyProvider.builder()
                    .id(UUID.randomUUID())
                    .name("p-" + type)
                    .status(status)
                    .providerType(type)
                    .build();
        }

        @Test
        void statusOnlyUsesStatusFinder() {
            when(providerRepository.findByStatus(WarrantyProvider.STATUS_ACTIVE))
                    .thenReturn(List.of(provider(WarrantyProvider.STATUS_ACTIVE, ProviderType.DEALER)));

            List<ProviderResponse> result = service.list(WarrantyProvider.STATUS_ACTIVE, null);

            assertThat(result).hasSize(1);
            verify(providerRepository, never()).findAll();
        }

        @Test
        void statusAndTypeNarrowsStatusResultByTypeInMemory() {
            when(providerRepository.findByStatus(WarrantyProvider.STATUS_ACTIVE))
                    .thenReturn(List.of(
                            provider(WarrantyProvider.STATUS_ACTIVE, ProviderType.DEALER),
                            provider(WarrantyProvider.STATUS_ACTIVE, ProviderType.MANUFACTURER)));

            List<ProviderResponse> result = service.list(WarrantyProvider.STATUS_ACTIVE, ProviderType.MANUFACTURER);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().providerType()).isEqualTo(ProviderType.MANUFACTURER);
        }

        @Test
        void typeOnlyUsesTypeFinder() {
            when(providerRepository.findByProviderType(ProviderType.THIRD_PARTY_ADMIN))
                    .thenReturn(List.of(provider(WarrantyProvider.STATUS_ACTIVE, ProviderType.THIRD_PARTY_ADMIN)));

            List<ProviderResponse> result = service.list(null, ProviderType.THIRD_PARTY_ADMIN);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().providerType()).isEqualTo(ProviderType.THIRD_PARTY_ADMIN);
        }

        @Test
        void unfilteredReturnsAll() {
            when(providerRepository.findAll())
                    .thenReturn(List.of(
                            provider(WarrantyProvider.STATUS_ACTIVE, ProviderType.DEALER),
                            provider(WarrantyProvider.STATUS_INACTIVE, ProviderType.MANUFACTURER)));

            assertThat(service.list(null, null)).hasSize(2);
        }
    }
}
