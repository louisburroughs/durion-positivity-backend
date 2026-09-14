package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.dto.ServiceAreaPostalCodesRequest;
import com.positivity.location.internal.dto.ServiceAreaRequest;
import com.positivity.location.internal.dto.ServiceAreaResponse;
import com.positivity.location.internal.entity.ServiceAreaEntity;
import com.positivity.location.internal.entity.ServiceAreaPostalCodeValue;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.ServiceAreaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service-layer RED tests for Service Area behavior.
 *
 * These tests define CRUD and postal-code list constraints for Story #76.
 *
 * Issue: #76
 */
@ExtendWith(MockitoExtension.class)
class ServiceAreaServiceTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    private static final String DOWNTOWN_AREA = "Downtown Area";

    @Mock
    private ServiceAreaRepository serviceAreaRepository;

    @InjectMocks
    private ServiceAreaServiceImpl service;

    @Test
    @DisplayName("#76 - create service area with postal codes succeeds")
    void shouldCreateServiceAreaWithPostalCodes() {
        ServiceAreaEntity persisted = ServiceAreaEntity.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .name(DOWNTOWN_AREA)
                .description("core coverage")
                .active(true)
                .postalCodes(Set.of(
                        ServiceAreaPostalCodeValue.builder()
                                .postalCode("94107")
                                .countryCode("US")
                                .build(),
                        ServiceAreaPostalCodeValue.builder()
                                .postalCode("94110")
                                .countryCode("US")
                                .build()))
                .createdAt(Instant.now(TEST_CLOCK))
                .updatedAt(Instant.now(TEST_CLOCK))
                .build();
        when(serviceAreaRepository.save(any(ServiceAreaEntity.class))).thenReturn(persisted);

        Map<String, Object> request = Map.of(
                "name",
                DOWNTOWN_AREA,
                "description",
                "core coverage",
                "active",
                true,
                "postalCodes",
                List.of(
                        Map.of("postalCode", "94107", "countryCode", "US"),
                        Map.of("postalCode", "94110", "countryCode", "US")));

        ServiceAreaResponse created = service.create(request);

        assertThat(created).isNotNull();
        assertThat(created.getName()).isEqualTo(DOWNTOWN_AREA);
        assertThat(created.getPostalCodes()).hasSize(2);
    }

    @Test
    @DisplayName("#76 - create duplicate service area name maps to conflict code")
    void shouldMapDuplicateNameConstraintToConflictCode() {
        when(serviceAreaRepository.save(any(ServiceAreaEntity.class)))
                .thenThrow(new DataIntegrityViolationException("violates service_areas_name_key"));

        Map<String, Object> request = Map.of(
                "name",
                DOWNTOWN_AREA,
                "description",
                "core coverage",
                "active",
                true,
                "postalCodes",
                List.of(Map.of("postalCode", "94107", "countryCode", "US")));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("SERVICE_AREA_NAME_TAKEN");
    }

    @Test
    @DisplayName("#76 - patch service area updates active flag and description")
    void shouldPatchServiceArea() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ServiceAreaEntity existing = ServiceAreaEntity.builder()
                .id(id)
                .name(DOWNTOWN_AREA)
                .description("old")
                .active(true)
                .build();
        when(serviceAreaRepository.findById(id)).thenReturn(java.util.Optional.of(existing));
        when(serviceAreaRepository.save(existing)).thenReturn(existing);

        ServiceAreaResponse updated =
                service.patch(id.toString(), Map.of("active", false, "description", "temporarily paused"));

        assertThat(updated).isNotNull();
        assertThat(updated.getActive()).isFalse();
        assertThat(updated.getDescription()).isEqualTo("temporarily paused");
    }

    @Test
    @DisplayName("#76 - create service area rejects empty postal-code list")
    void shouldRejectEmptyPostalCodeList() {
        Map<String, Object> request = Map.of("name", "Invalid Area", "active", true, "postalCodes", List.of());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("service area must include at least one postal code");
    }

    @Test
    @DisplayName("#76 - create service area rejects missing countryCode")
    void shouldRejectPostalCodeWithoutCountryCode() {
        Map<String, Object> request = Map.of(
                "name", "Invalid Country", "active", true, "postalCodes", List.of(Map.of("postalCode", "10001")));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("postal code entries require countryCode");
    }

    @Test
    @DisplayName("#76 - create defaults active=true when omitted")
    void shouldDefaultActiveToTrueWhenMissing() {
        ServiceAreaEntity persisted = ServiceAreaEntity.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .name("Default Active")
                .description("desc")
                .active(true)
                .postalCodes(Set.of(ServiceAreaPostalCodeValue.builder()
                        .postalCode("10001")
                        .countryCode("US")
                        .build()))
                .build();
        when(serviceAreaRepository.save(any(ServiceAreaEntity.class))).thenReturn(persisted);

        ServiceAreaResponse created = service.create(Map.of(
                "name", "Default Active",
                "description", "desc",
                "postalCodes", List.of(Map.of("postalCode", "10001", "countryCode", "US"))));

        assertThat(created.getActive()).isTrue();
    }

    @Test
    @DisplayName("#76 - patch with invalid id returns bad request")
    void shouldRejectPatchWhenIdIsInvalid() {
        assertThatThrownBy(() -> service.patch("bad-id", Map.of("description", "none", "active", false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        verify(serviceAreaRepository, never()).save(any(ServiceAreaEntity.class));
    }

    @Test
    @DisplayName("#76 - patch not found throws not found")
    void shouldThrowWhenPatchTargetMissing() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(serviceAreaRepository.findById(id)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.patch(id.toString(), Map.of("description", "none", "active", false)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Service area not found");
        verify(serviceAreaRepository, never()).save(any(ServiceAreaEntity.class));
    }

    @Test
    @DisplayName("#76 - list returns mapped service areas")
    void shouldListServiceAreas() {
        ServiceAreaEntity first = ServiceAreaEntity.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .name("A")
                .active(true)
                .postalCodes(Set.of(ServiceAreaPostalCodeValue.builder()
                        .postalCode("11111")
                        .countryCode("US")
                        .build()))
                .build();
        ServiceAreaEntity second = ServiceAreaEntity.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .name("B")
                .active(false)
                .postalCodes(Set.of(ServiceAreaPostalCodeValue.builder()
                        .postalCode("22222")
                        .countryCode("US")
                        .build()))
                .build();
        when(serviceAreaRepository.findAll()).thenReturn(List.of(first, second));

        List<ServiceAreaResponse> result = service.list();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("A");
        assertThat(result.get(1).getActive()).isFalse();
    }

    @Test
    @DisplayName("#76 - create removes duplicate postal entries via set semantics")
    void shouldDeduplicatePostalEntriesOnCreate() {
        ServiceAreaEntity persisted = ServiceAreaEntity.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .name("Dedup")
                .active(true)
                .postalCodes(Set.of(ServiceAreaPostalCodeValue.builder()
                        .postalCode("30301")
                        .countryCode("US")
                        .build()))
                .build();
        when(serviceAreaRepository.save(any(ServiceAreaEntity.class))).thenReturn(persisted);

        ServiceAreaResponse created = service.create(Map.of(
                "name",
                "Dedup",
                "postalCodes",
                List.of(
                        Map.of("postalCode", "30301", "countryCode", "US"),
                        Map.of("postalCode", "30301", "countryCode", "US"))));

        assertThat(created.getPostalCodes()).hasSize(1);
    }

    private static ServiceAreaEntity areaWith(UUID id, String... postalCodes) {
        Set<ServiceAreaPostalCodeValue> values = new java.util.LinkedHashSet<>();
        for (String postalCode : postalCodes) {
            values.add(ServiceAreaPostalCodeValue.builder()
                    .postalCode(postalCode)
                    .countryCode("US")
                    .build());
        }
        return ServiceAreaEntity.builder()
                .id(id)
                .name(DOWNTOWN_AREA)
                .active(true)
                .postalCodes(values)
                .build();
    }

    private static ServiceAreaPostalCodesRequest replacementOf(String... postalCodes) {
        return ServiceAreaPostalCodesRequest.builder()
                .postalCodes(java.util.Arrays.stream(postalCodes)
                        .map(code -> ServiceAreaRequest.PostalCodeEntry.builder()
                                .postalCode(code)
                                .countryCode("US")
                                .build())
                        .toList())
                .build();
    }

    @Test
    @DisplayName("#1991 - replacing postal codes swaps the whole set, adding and removing in one call")
    void shouldReplaceTheWholePostalCodeSet() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000010");
        ServiceAreaEntity existing = areaWith(id, "94107", "94110");
        when(serviceAreaRepository.findById(id)).thenReturn(Optional.of(existing));
        when(serviceAreaRepository.save(any(ServiceAreaEntity.class))).thenAnswer(call -> call.getArgument(0));

        ServiceAreaResponse updated = service.replacePostalCodes(id.toString(), replacementOf("94110", "94112"));

        // 94107 is gone because it was not sent: this is a replace, not a merge.
        assertThat(updated.getPostalCodes())
                .extracting(ServiceAreaRequest.PostalCodeEntry::getPostalCode)
                .containsExactlyInAnyOrder("94110", "94112");
    }

    @Test
    @DisplayName("#1991 - the loaded collection is mutated in place rather than swapped")
    void shouldMutateTheManagedCollectionRatherThanReplaceTheReference() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000011");
        ServiceAreaEntity existing = areaWith(id, "94107");
        Set<ServiceAreaPostalCodeValue> loaded = existing.getPostalCodes();
        when(serviceAreaRepository.findById(id)).thenReturn(Optional.of(existing));
        when(serviceAreaRepository.save(any(ServiceAreaEntity.class))).thenAnswer(call -> call.getArgument(0));

        service.replacePostalCodes(id.toString(), replacementOf("94112"));

        // Hibernate tracks the instance it loaded for an @ElementCollection; swapping the reference
        // makes it delete every row and reinsert instead of writing the difference.
        assertThat(existing.getPostalCodes()).isSameAs(loaded);
        assertThat(loaded).extracting(ServiceAreaPostalCodeValue::getPostalCode).containsExactly("94112");
    }

    @Test
    @DisplayName("#1991 - an empty replacement set is refused rather than clearing coverage")
    void shouldRefuseAnEmptyReplacementSet() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000012");
        when(serviceAreaRepository.findById(id)).thenReturn(Optional.of(areaWith(id, "94107")));

        assertThatThrownBy(() -> service.replacePostalCodes(
                        id.toString(),
                        ServiceAreaPostalCodesRequest.builder()
                                .postalCodes(List.of())
                                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one postal code");

        verify(serviceAreaRepository, never()).save(any(ServiceAreaEntity.class));
    }

    @Test
    @DisplayName("#1991 - a replacement entry without a countryCode is refused")
    void shouldRefuseAPostalCodeWithoutACountryCode() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000013");
        when(serviceAreaRepository.findById(id)).thenReturn(Optional.of(areaWith(id, "94107")));
        ServiceAreaPostalCodesRequest request = ServiceAreaPostalCodesRequest.builder()
                .postalCodes(List.of(ServiceAreaRequest.PostalCodeEntry.builder()
                        .postalCode("94112")
                        .build()))
                .build();

        assertThatThrownBy(() -> service.replacePostalCodes(id.toString(), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("countryCode");

        verify(serviceAreaRepository, never()).save(any(ServiceAreaEntity.class));
    }

    @Test
    @DisplayName("#1991 - replacing postal codes on an unknown service area is a 404")
    void shouldRejectReplacementForUnknownServiceArea() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000014");
        when(serviceAreaRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replacePostalCodes(id.toString(), replacementOf("94112")))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(serviceAreaRepository, never()).save(any(ServiceAreaEntity.class));
    }

    @Test
    @DisplayName("#1991 - a malformed service area id is a 400, not a 404")
    void shouldRejectAMalformedServiceAreaId() {
        assertThatThrownBy(() -> service.replacePostalCodes("not-a-uuid", replacementOf("94112")))
                .isInstanceOf(ResponseStatusException.class);

        verify(serviceAreaRepository, never()).save(any(ServiceAreaEntity.class));
    }
}
