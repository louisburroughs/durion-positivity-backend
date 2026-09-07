package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.ServicePackageMemberRequestDto;
import com.positivity.catalog.internal.dto.ServicePackageRequestDto;
import com.positivity.catalog.internal.dto.ServicePackageResponseDto;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.entity.ServicePackageEntity;
import com.positivity.catalog.internal.entity.ServicePackageMemberEntity;
import com.positivity.catalog.internal.enums.LaborStandardOwnerScope;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.ServicePackageMemberRepository;
import com.positivity.catalog.internal.repository.ServicePackageRepository;
import com.positivity.catalog.internal.repository.ServiceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Service packages and fleet requirement sets (#1575 Tier 0, T0-4): composition is validated
 * against the real catalog at authoring time, membership is unique per operation, and a fleet
 * requirement set is a package scoped to one account rather than a second kind of thing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ServicePackageServiceImpl")
class ServicePackageServiceImplTest {

    private static final UUID PACKAGE_ID = UUID.fromString("0198f2a1-1111-7000-8000-000000000001");
    private static final UUID SERVICE_ID = UUID.fromString("0198f2a1-1111-7000-8000-000000000002");
    private static final UUID SHOP_A = UUID.fromString("0198f2a1-0000-7000-8000-00000000000a");
    private static final UUID FLEET_PARTY = UUID.fromString("0198f2a1-0000-7000-8000-0000000000f1");

    @Mock
    private ServicePackageRepository packageRepository;

    @Mock
    private ServicePackageMemberRepository memberRepository;

    @Mock
    private ServiceRepository serviceRepository;

    private ServicePackageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ServicePackageServiceImpl(packageRepository, memberRepository, serviceRepository);
        when(packageRepository.findByPackageCode(any())).thenReturn(Optional.empty());
        when(packageRepository.save(any())).thenAnswer(inv -> {
            ServicePackageEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(PACKAGE_ID);
            }
            return entity;
        });
        when(packageRepository.findById(PACKAGE_ID)).thenReturn(Optional.of(existingPackage()));
        when(memberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(memberRepository.findByPackageIdOrderBySequenceAsc(any())).thenReturn(List.of());
        when(memberRepository.findByPackageIdInOrderBySequenceAsc(anyList())).thenReturn(List.of());
        when(memberRepository.existsByPackageIdAndServiceId(any(), any())).thenReturn(false);
        when(serviceRepository.existsById(any())).thenReturn(true);
        when(serviceRepository.findAllById(any())).thenReturn(List.of());
    }

    private static ServicePackageEntity existingPackage() {
        ServicePackageEntity entity = new ServicePackageEntity();
        entity.setId(PACKAGE_ID);
        entity.setPackageCode("TIRE-INSTALL-PKG-4");
        entity.setName("Four Tire Installation Package");
        entity.setOwnerScope(LaborStandardOwnerScope.PLATFORM);
        entity.setActive(true);
        return entity;
    }

    private static ServicePackageRequestDto request() {
        ServicePackageRequestDto request = new ServicePackageRequestDto();
        request.setPackageCode("TIRE-INSTALL-PKG-4");
        request.setName("Four Tire Installation Package");
        request.setPackageLaborHours(new BigDecimal("1.6"));
        return request;
    }

    @Nested
    @DisplayName("creating a package")
    class Creating {

        @Test
        @DisplayName("stores the authored hours and defaults to an active platform package")
        void storesAuthoredHours() {
            ServicePackageResponseDto response = service.create(request());

            assertThat(response.getPackageLaborHours()).isEqualByComparingTo("1.6");
            assertThat(response.getOwnerScope()).isEqualTo("PLATFORM");
            assertThat(response.isActive()).isTrue();
            assertThat(response.getMembers()).isEmpty();
        }

        @Test
        @DisplayName("a duplicate package code is refused — the code is the package's identity")
        void duplicateCodeRefused() {
            when(packageRepository.findByPackageCode("TIRE-INSTALL-PKG-4")).thenReturn(Optional.of(existingPackage()));

            assertThatThrownBy(() -> service.create(request()))
                    .isInstanceOf(CatalogBusinessRuleException.class)
                    .hasMessageContaining("already exists");
            verify(packageRepository, never()).save(any());
        }

        @Test
        @DisplayName("a malformed package code is refused before anything is stored")
        void malformedCodeRefused() {
            ServicePackageRequestDto bad = request();
            bad.setPackageCode("tire install pkg");

            assertThatThrownBy(() -> service.create(bad)).isInstanceOf(CatalogValidationException.class);
        }

        @Test
        @DisplayName("hours finer than tenths are refused — book time is published in tenths")
        void subTenthHoursRefused() {
            ServicePackageRequestDto bad = request();
            bad.setPackageLaborHours(new BigDecimal("1.65"));

            assertThatThrownBy(() -> service.create(bad))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("packageLaborHours");
        }

        @Test
        @DisplayName("SHOP without a location is refused, as it is for a labor standard")
        void shopWithoutLocationRefused() {
            ServicePackageRequestDto bad = request();
            bad.setOwnerScope("SHOP");

            assertThatThrownBy(() -> service.create(bad))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("ownerLocationId is required");
        }

        @Test
        @DisplayName("a shop's own package records its owning location")
        void shopPackageRecordsItsOwner() {
            ServicePackageRequestDto shopRequest = request();
            shopRequest.setOwnerScope("SHOP");
            shopRequest.setOwnerLocationId(SHOP_A);

            assertThat(service.create(shopRequest).getOwnerLocationId()).isEqualTo(SHOP_A);
        }

        @Test
        @DisplayName("a fleet requirement set is a package with a fleet party, not a separate kind of thing")
        void fleetRequirementSetIsAPackage() {
            ServicePackageRequestDto fleetRequest = request();
            fleetRequest.setPackageCode("FLEET-REQ-MERIDIAN");
            fleetRequest.setFleetPartyId(FLEET_PARTY);

            assertThat(service.create(fleetRequest).getFleetPartyId()).isEqualTo(FLEET_PARTY);
        }

        @Test
        @DisplayName("an effective window that ends before it starts is refused")
        void invertedWindowRefused() {
            ServicePackageRequestDto bad = request();
            bad.setEffectiveFrom(LocalDate.of(2026, 6, 1));
            bad.setEffectiveTo(LocalDate.of(2026, 5, 1));

            assertThatThrownBy(() -> service.create(bad))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("effectiveTo");
        }
    }

    @Nested
    @DisplayName("composing members")
    class Composing {

        private static ServicePackageMemberRequestDto memberRequest() {
            ServicePackageMemberRequestDto request = new ServicePackageMemberRequestDto();
            request.setServiceId(SERVICE_ID);
            return request;
        }

        @Test
        @DisplayName("a member defaults to required — an upsell has to say it is optional")
        void memberDefaultsToRequired() {
            service.addMember(PACKAGE_ID, memberRequest());

            ArgumentCaptor<ServicePackageMemberEntity> captor =
                    ArgumentCaptor.forClass(ServicePackageMemberEntity.class);
            verify(memberRepository).save(captor.capture());
            assertThat(captor.getValue().isRequired()).isTrue();
            assertThat(captor.getValue().getQuantity()).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("an omitted sequence appends after the current last member")
        void omittedSequenceAppends() {
            ServicePackageMemberEntity existing = new ServicePackageMemberEntity();
            existing.setId(UUID.randomUUID());
            existing.setPackageId(PACKAGE_ID);
            existing.setServiceId(UUID.randomUUID());
            existing.setSequence(30);
            when(memberRepository.findByPackageIdOrderBySequenceAsc(PACKAGE_ID)).thenReturn(List.of(existing));

            service.addMember(PACKAGE_ID, memberRequest());

            ArgumentCaptor<ServicePackageMemberEntity> captor =
                    ArgumentCaptor.forClass(ServicePackageMemberEntity.class);
            verify(memberRepository).save(captor.capture());
            assertThat(captor.getValue().getSequence()).isEqualTo(40);
        }

        @Test
        @DisplayName("a service that does not exist is refused at authoring time, not at quote time")
        void unknownServiceRefused() {
            when(serviceRepository.existsById(SERVICE_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.addMember(PACKAGE_ID, memberRequest()))
                    .isInstanceOf(CatalogNotFoundException.class)
                    .hasMessageContaining("Service not found");
            verify(memberRepository, never()).save(any());
        }

        @Test
        @DisplayName("adding the same operation twice is refused — wanting two is a quantity")
        void duplicateMemberRefused() {
            when(memberRepository.existsByPackageIdAndServiceId(PACKAGE_ID, SERVICE_ID))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.addMember(PACKAGE_ID, memberRequest()))
                    .isInstanceOf(CatalogBusinessRuleException.class)
                    .hasMessageContaining("already a member");
        }

        @Test
        @DisplayName("a zero or negative quantity is refused")
        void nonPositiveQuantityRefused() {
            ServicePackageMemberRequestDto bad = memberRequest();
            bad.setQuantity(BigDecimal.ZERO);

            assertThatThrownBy(() -> service.addMember(PACKAGE_ID, bad))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("quantity");
        }

        @Test
        @DisplayName("removing a membership that belongs to another package is a 404, not a cross-package delete")
        void removingAnotherPackagesMemberIs404() {
            UUID memberId = UUID.randomUUID();
            ServicePackageMemberEntity foreign = new ServicePackageMemberEntity();
            foreign.setId(memberId);
            foreign.setPackageId(UUID.randomUUID());
            when(memberRepository.findById(memberId)).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> service.removeMember(PACKAGE_ID, memberId))
                    .isInstanceOf(CatalogNotFoundException.class);
            verify(memberRepository, never()).delete(any());
        }

        @Test
        @DisplayName("a member response carries the operation code, so a package reads without a second lookup")
        void memberResponseCarriesTheOperationCode() {
            ServicePackageMemberEntity member = new ServicePackageMemberEntity();
            member.setId(UUID.randomUUID());
            member.setPackageId(PACKAGE_ID);
            member.setServiceId(SERVICE_ID);
            member.setSequence(10);
            member.setQuantity(BigDecimal.ONE);
            member.setRequired(true);
            when(memberRepository.findByPackageIdOrderBySequenceAsc(PACKAGE_ID)).thenReturn(List.of(member));

            ServiceEntity serviceEntity = new ServiceEntity();
            serviceEntity.setId(SERVICE_ID);
            serviceEntity.setName("Wheel Balance - Set of 4");
            serviceEntity.setOperationCode("WHEEL-BALANCE-SET-4");
            when(serviceRepository.findAllById(any())).thenReturn(List.of(serviceEntity));

            ServicePackageResponseDto response = service.get(PACKAGE_ID);

            assertThat(response.getMembers()).hasSize(1);
            assertThat(response.getMembers().get(0).getOperationCode()).isEqualTo("WHEEL-BALANCE-SET-4");
            assertThat(response.getMembers().get(0).getServiceName()).isEqualTo("Wheel Balance - Set of 4");
        }
    }

    @Nested
    @DisplayName("listing")
    class Listing {

        @Test
        @DisplayName("naming a fleet party includes its requirement set without also setting the flag")
        void namingAFleetIncludesItsRequirementSet() {
            service.list(SHOP_A, FLEET_PARTY, false);

            verify(packageRepository).findSellable(SHOP_A, FLEET_PARTY, true);
        }

        @Test
        @DisplayName("a general listing excludes fleet requirement sets — they belong to one account")
        void generalListingExcludesFleetSets() {
            service.list(SHOP_A, null, false);

            verify(packageRepository).findSellable(SHOP_A, null, false);
        }

        @Test
        @DisplayName("an empty result skips the member batch read entirely")
        void emptyResultSkipsTheMemberRead() {
            when(packageRepository.findSellable(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenReturn(List.of());

            assertThat(service.list(SHOP_A, null, false)).isEmpty();
            verify(memberRepository, never()).findByPackageIdInOrderBySequenceAsc(anyList());
        }

        @Test
        @DisplayName("members are read in one batch, not once per package")
        void membersReadInOneBatch() {
            ServicePackageEntity second = existingPackage();
            second.setId(UUID.fromString("0198f2a1-1111-7000-8000-000000000003"));
            second.setPackageCode("SEASONAL-CHANGEOVER");
            when(packageRepository.findSellable(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenReturn(List.of(existingPackage(), second));

            service.list(SHOP_A, null, false);

            verify(memberRepository).findByPackageIdInOrderBySequenceAsc(List.of(PACKAGE_ID, second.getId()));
            verify(memberRepository, never()).findByPackageIdOrderBySequenceAsc(any());
        }
    }
}
