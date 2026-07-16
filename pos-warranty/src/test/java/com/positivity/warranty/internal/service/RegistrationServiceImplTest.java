package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.warranty.internal.dto.RegistrationRequest;
import com.positivity.warranty.internal.dto.RegistrationResponse;
import com.positivity.warranty.internal.entity.WarrantyRegistration;
import com.positivity.warranty.internal.enums.RegistrationStatus;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.repository.WarrantyPolicyRepository;
import com.positivity.warranty.internal.repository.WarrantyRegistrationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Sold-coverage registration CRUD (PRD §3.3): policy existence guard, ACTIVE default,
 * customer/vehicle/status search branches.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceImplTest {

    private static final UUID REGISTRATION_ID = UUID.fromString("018f0000-0000-7000-8000-000000000401");
    private static final UUID POLICY_ID = UUID.fromString("018f0000-0000-7000-8000-000000000402");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000403");
    private static final UUID VEHICLE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000404");
    private static final UUID INVOICE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000405");
    private static final UUID INVOICE_LINE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000406");
    private static final LocalDate PURCHASE_DATE = LocalDate.of(2026, 5, 1);

    @Mock
    private WarrantyRegistrationRepository registrationRepository;

    @Mock
    private WarrantyPolicyRepository policyRepository;

    @InjectMocks
    private RegistrationServiceImpl service;

    private static RegistrationRequest request(RegistrationStatus status) {
        return new RegistrationRequest(
                POLICY_ID,
                CUSTOMER_ID,
                VEHICLE_ID,
                INVOICE_ID,
                INVOICE_LINE_ID,
                "RH-2026-88412",
                PURCHASE_DATE,
                PURCHASE_DATE.plusYears(3),
                status);
    }

    private static WarrantyRegistration registration(RegistrationStatus status) {
        return WarrantyRegistration.builder()
                .id(REGISTRATION_ID)
                .policyId(POLICY_ID)
                .customerId(CUSTOMER_ID)
                .vehicleId(VEHICLE_ID)
                .purchaseDate(PURCHASE_DATE)
                .status(status)
                .version(1L)
                .build();
    }

    private void stubPolicyExists() {
        when(policyRepository.existsById(POLICY_ID)).thenReturn(true);
    }

    private void stubSaveEcho() {
        when(registrationRepository.save(any(WarrantyRegistration.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    class Create {

        @Test
        void mapsFieldsAndDefaultsStatusToActive() {
            stubPolicyExists();
            stubSaveEcho();

            RegistrationResponse response = service.create(request(null));

            assertThat(response.policyId()).isEqualTo(POLICY_ID);
            assertThat(response.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(response.vehicleId()).isEqualTo(VEHICLE_ID);
            assertThat(response.sourceInvoiceId()).isEqualTo(INVOICE_ID);
            assertThat(response.sourceInvoiceLineId()).isEqualTo(INVOICE_LINE_ID);
            assertThat(response.contractNumber()).isEqualTo("RH-2026-88412");
            assertThat(response.purchaseDate()).isEqualTo(PURCHASE_DATE);
            assertThat(response.expiresAt()).isEqualTo(PURCHASE_DATE.plusYears(3));
            assertThat(response.status()).isEqualTo(RegistrationStatus.ACTIVE);
        }

        @Test
        void keepsExplicitStatus() {
            stubPolicyExists();
            stubSaveEcho();

            RegistrationResponse response = service.create(request(RegistrationStatus.EXPIRED));

            assertThat(response.status()).isEqualTo(RegistrationStatus.EXPIRED);
        }

        @Test
        void unknownPolicyIs404AndNothingSaved() {
            when(policyRepository.existsById(POLICY_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.create(request(null)))
                    .isInstanceOf(WarrantyNotFoundException.class)
                    .satisfies(ex -> assertThat(((WarrantyNotFoundException) ex).getCode())
                            .isEqualTo(PolicyServiceImpl.NOT_FOUND_CODE));
            verify(registrationRepository, never()).save(any());
        }
    }

    @Nested
    class Update {

        @Test
        void overwritesExistingRegistration() {
            when(registrationRepository.findById(REGISTRATION_ID))
                    .thenReturn(Optional.of(registration(RegistrationStatus.ACTIVE)));
            stubPolicyExists();
            stubSaveEcho();

            RegistrationResponse response = service.update(REGISTRATION_ID, request(RegistrationStatus.CANCELLED));

            assertThat(response.id()).isEqualTo(REGISTRATION_ID);
            assertThat(response.status()).isEqualTo(RegistrationStatus.CANCELLED);
            assertThat(response.contractNumber()).isEqualTo("RH-2026-88412");
        }

        @Test
        void nullStatusInRequestKeepsExistingStatus() {
            when(registrationRepository.findById(REGISTRATION_ID))
                    .thenReturn(Optional.of(registration(RegistrationStatus.EXHAUSTED)));
            stubPolicyExists();
            stubSaveEcho();

            RegistrationResponse response = service.update(REGISTRATION_ID, request(null));

            assertThat(response.status()).isEqualTo(RegistrationStatus.EXHAUSTED);
        }

        @Test
        void missingRegistrationIs404WithCode() {
            when(registrationRepository.findById(REGISTRATION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(REGISTRATION_ID, request(null)))
                    .isInstanceOf(WarrantyNotFoundException.class)
                    .satisfies(ex -> assertThat(((WarrantyNotFoundException) ex).getCode())
                            .isEqualTo(RegistrationServiceImpl.NOT_FOUND_CODE));
        }

        @Test
        void unknownPolicyOnUpdateIs404() {
            when(registrationRepository.findById(REGISTRATION_ID))
                    .thenReturn(Optional.of(registration(RegistrationStatus.ACTIVE)));
            when(policyRepository.existsById(POLICY_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.update(REGISTRATION_ID, request(null)))
                    .isInstanceOf(WarrantyNotFoundException.class);
            verify(registrationRepository, never()).save(any());
        }
    }

    @Nested
    class GetById {

        @Test
        void mapsEntity() {
            when(registrationRepository.findById(REGISTRATION_ID))
                    .thenReturn(Optional.of(registration(RegistrationStatus.ACTIVE)));

            RegistrationResponse response = service.getById(REGISTRATION_ID);

            assertThat(response.id()).isEqualTo(REGISTRATION_ID);
            assertThat(response.policyId()).isEqualTo(POLICY_ID);
            assertThat(response.version()).isEqualTo(1L);
        }

        @Test
        void missingRegistrationIs404() {
            when(registrationRepository.findById(REGISTRATION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(REGISTRATION_ID))
                    .isInstanceOf(WarrantyNotFoundException.class)
                    .satisfies(ex -> assertThat(((WarrantyNotFoundException) ex).getCode())
                            .isEqualTo(RegistrationServiceImpl.NOT_FOUND_CODE));
        }
    }

    @Nested
    class Search {

        @Test
        void customerAndVehicleUsesCompositeFinder() {
            when(registrationRepository.findByCustomerIdAndVehicleId(CUSTOMER_ID, VEHICLE_ID))
                    .thenReturn(List.of(registration(RegistrationStatus.ACTIVE)));

            assertThat(service.search(CUSTOMER_ID, VEHICLE_ID, null)).hasSize(1);
            verify(registrationRepository, never()).findAll();
        }

        @Test
        void customerAndVehicleStillNarrowsByStatusInMemory() {
            when(registrationRepository.findByCustomerIdAndVehicleId(CUSTOMER_ID, VEHICLE_ID))
                    .thenReturn(List.of(
                            registration(RegistrationStatus.ACTIVE), registration(RegistrationStatus.CANCELLED)));

            List<RegistrationResponse> result = service.search(CUSTOMER_ID, VEHICLE_ID, RegistrationStatus.CANCELLED);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().status()).isEqualTo(RegistrationStatus.CANCELLED);
        }

        @Test
        void customerAndStatusUsesStatusFinder() {
            when(registrationRepository.findByCustomerIdAndStatus(CUSTOMER_ID, RegistrationStatus.ACTIVE))
                    .thenReturn(List.of(registration(RegistrationStatus.ACTIVE)));

            assertThat(service.search(CUSTOMER_ID, null, RegistrationStatus.ACTIVE))
                    .hasSize(1);
        }

        @Test
        void customerOnlyUsesCustomerFinder() {
            when(registrationRepository.findByCustomerId(CUSTOMER_ID))
                    .thenReturn(List.of(registration(RegistrationStatus.ACTIVE)));

            assertThat(service.search(CUSTOMER_ID, null, null)).hasSize(1);
        }

        @Test
        void vehicleOnlyUsesVehicleFinder() {
            when(registrationRepository.findByVehicleId(VEHICLE_ID))
                    .thenReturn(List.of(registration(RegistrationStatus.ACTIVE)));

            assertThat(service.search(null, VEHICLE_ID, null)).hasSize(1);
        }

        @Test
        void statusOnlyFiltersFindAllInMemory() {
            when(registrationRepository.findAll())
                    .thenReturn(
                            List.of(registration(RegistrationStatus.ACTIVE), registration(RegistrationStatus.EXPIRED)));

            List<RegistrationResponse> result = service.search(null, null, RegistrationStatus.EXPIRED);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().status()).isEqualTo(RegistrationStatus.EXPIRED);
        }

        @Test
        void unfilteredReturnsAll() {
            when(registrationRepository.findAll())
                    .thenReturn(
                            List.of(registration(RegistrationStatus.ACTIVE), registration(RegistrationStatus.EXPIRED)));

            assertThat(service.search(null, null, null)).hasSize(2);
        }
    }
}
