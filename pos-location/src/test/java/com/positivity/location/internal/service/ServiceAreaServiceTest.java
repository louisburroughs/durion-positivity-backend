package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.dto.ServiceAreaPostalCodesRequest;
import com.positivity.location.internal.dto.ServiceAreaRequest;
import com.positivity.location.internal.dto.ServiceAreaResponse;
import com.positivity.location.internal.entity.ServiceAreaEntity;
import com.positivity.location.internal.entity.ServiceAreaPostalCodeValue;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.InvalidFieldException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.ServiceAreaRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
        when(serviceAreaRepository.saveAndFlush(any(ServiceAreaEntity.class))).thenReturn(persisted);

        ServiceAreaRequest request = ServiceAreaRequest.builder()
                .name(DOWNTOWN_AREA)
                .description("core coverage")
                .active(true)
                .postalCodes(List.of(entry("94107", "US"), entry("94110", "US")))
                .build();

        ServiceAreaResponse created = service.create(request);

        assertThat(created).isNotNull();
        assertThat(created.getName()).isEqualTo(DOWNTOWN_AREA);
        assertThat(created.getPostalCodes()).hasSize(2);
    }

    @Test
    @DisplayName("#76 - create duplicate service area name maps to conflict code")
    void shouldMapDuplicateNameConstraintToConflictCode() {
        when(serviceAreaRepository.saveAndFlush(any(ServiceAreaEntity.class)))
                .thenThrow(constraintViolation("violates service_areas_name_key"));

        ServiceAreaRequest request = ServiceAreaRequest.builder()
                .name(DOWNTOWN_AREA)
                .description("core coverage")
                .active(true)
                .postalCodes(List.of(entry("94107", "US")))
                .build();

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
        when(serviceAreaRepository.saveAndFlush(existing)).thenReturn(existing);

        ServiceAreaResponse updated =
                service.patch(id.toString(), Map.of("active", false, "description", "temporarily paused"));

        assertThat(updated).isNotNull();
        assertThat(updated.getActive()).isFalse();
        assertThat(updated.getDescription()).isEqualTo("temporarily paused");
    }

    @Test
    @DisplayName("#76 - create service area rejects empty postal-code list")
    void shouldRejectEmptyPostalCodeList() {
        ServiceAreaRequest request = ServiceAreaRequest.builder()
                .name("Invalid Area")
                .active(true)
                .postalCodes(List.of())
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("service area must include at least one postal code");
    }

    @Test
    @DisplayName("#76 - create service area rejects missing countryCode")
    void shouldRejectPostalCodeWithoutCountryCode() {
        ServiceAreaRequest request = ServiceAreaRequest.builder()
                .name("Invalid Country")
                .active(true)
                .postalCodes(List.of(ServiceAreaRequest.PostalCodeEntry.builder()
                        .postalCode("10001")
                        .build()))
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("postal code entries require countryCode");
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
        when(serviceAreaRepository.saveAndFlush(any(ServiceAreaEntity.class))).thenReturn(persisted);

        ServiceAreaResponse created = service.create(ServiceAreaRequest.builder()
                .name("Default Active")
                .description("desc")
                .postalCodes(List.of(entry("10001", "US")))
                .build());

        assertThat(created.getActive()).isTrue();
    }

    @Test
    @DisplayName("#76 - patch with invalid id returns bad request")
    void shouldRejectPatchWhenIdIsInvalid() {
        assertThatThrownBy(() -> service.patch("bad-id", Map.of("description", "none", "active", false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        verify(serviceAreaRepository, never()).saveAndFlush(any(ServiceAreaEntity.class));
    }

    @Test
    @DisplayName("#76 - patch not found throws not found")
    void shouldThrowWhenPatchTargetMissing() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(serviceAreaRepository.findById(id)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.patch(id.toString(), Map.of("description", "none", "active", false)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Service area not found");
        verify(serviceAreaRepository, never()).saveAndFlush(any(ServiceAreaEntity.class));
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
        when(serviceAreaRepository.saveAndFlush(any(ServiceAreaEntity.class))).thenReturn(persisted);

        ServiceAreaResponse created = service.create(ServiceAreaRequest.builder()
                .name("Dedup")
                .postalCodes(List.of(entry("30301", "US"), entry("30301", "US")))
                .build());

        assertThat(created.getPostalCodes()).hasSize(1);
    }

    private static ServiceAreaRequest.PostalCodeEntry entry(String postalCode, String countryCode) {
        return ServiceAreaRequest.PostalCodeEntry.builder()
                .postalCode(postalCode)
                .countryCode(countryCode)
                .build();
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
        when(serviceAreaRepository.saveAndFlush(any(ServiceAreaEntity.class))).thenAnswer(call -> call.getArgument(0));

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
        when(serviceAreaRepository.saveAndFlush(any(ServiceAreaEntity.class))).thenAnswer(call -> call.getArgument(0));

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
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least one postal code");

        verify(serviceAreaRepository, never()).saveAndFlush(any(ServiceAreaEntity.class));
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
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("countryCode");

        verify(serviceAreaRepository, never()).saveAndFlush(any(ServiceAreaEntity.class));
    }

    @Test
    @DisplayName("#1991 - replacing postal codes on an unknown service area is a 404")
    void shouldRejectReplacementForUnknownServiceArea() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000014");
        when(serviceAreaRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replacePostalCodes(id.toString(), replacementOf("94112")))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(serviceAreaRepository, never()).saveAndFlush(any(ServiceAreaEntity.class));
    }

    @Test
    @DisplayName("#1991 - a malformed service area id is a 400, not a 404")
    void shouldRejectAMalformedServiceAreaId() {
        assertThatThrownBy(() -> service.replacePostalCodes("not-a-uuid", replacementOf("94112")))
                .isInstanceOf(ResponseStatusException.class);

        verify(serviceAreaRepository, never()).saveAndFlush(any(ServiceAreaEntity.class));
    }

    // ---------------------------------------------------------------- #2256

    private ServiceAreaEntity stubExistingArea(UUID id) {
        ServiceAreaEntity existing = ServiceAreaEntity.builder()
                .id(id)
                .name(DOWNTOWN_AREA)
                .description("old")
                .active(true)
                .build();
        when(serviceAreaRepository.findById(id)).thenReturn(Optional.of(existing));
        return existing;
    }

    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    private void assertPatchRefused(UUID id, Map<String, Object> patch, String field) {
        assertThatThrownBy(() -> service.patch(id.toString(), patch))
                .isInstanceOfSatisfying(InvalidFieldException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(e.getField()).isEqualTo(field);
                });
        verify(serviceAreaRepository, never()).saveAndFlush(any(ServiceAreaEntity.class));
    }

    @Test
    @DisplayName("#2256 - patch renames the area and trims the new name")
    void shouldRenameOnPatch() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000020");
        ServiceAreaEntity existing = stubExistingArea(id);
        when(serviceAreaRepository.saveAndFlush(existing)).thenReturn(existing);

        ServiceAreaResponse updated = service.patch(id.toString(), Map.of("name", "  Uptown Area "));

        assertThat(updated.getName()).isEqualTo("Uptown Area");
        assertThat(existing.getName()).isEqualTo("Uptown Area");
    }

    @Test
    @DisplayName("#2256 - patch rename onto a taken name is 409 SERVICE_AREA_NAME_TAKEN")
    void shouldReportNameTakenOnRename() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000021");
        stubExistingArea(id);
        when(serviceAreaRepository.saveAndFlush(any(ServiceAreaEntity.class)))
                .thenThrow(constraintViolation(
                        "duplicate key value violates unique constraint \"service_areas_name_key\""));

        assertThatThrownBy(() -> service.patch(id.toString(), Map.of("name", "Taken")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("SERVICE_AREA_NAME_TAKEN");
    }

    @Test
    @DisplayName("#2256 - patch name must be non-blank text")
    void shouldRefuseABlankOrNonTextName() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000022");
        stubExistingArea(id);

        assertPatchRefused(id, Map.of("name", "   "), "name");
        assertPatchRefused(id, mapOf("name", null), "name");
        assertPatchRefused(id, Map.of("name", 42), "name");
        assertPatchRefused(id, Map.of("name", "x".repeat(256)), "name");
    }

    @Test
    @DisplayName("#2256 - patch active accepts only a JSON boolean")
    void shouldRefuseANonBooleanActive() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000023");
        ServiceAreaEntity existing = stubExistingArea(id);

        assertPatchRefused(id, mapOf("active", null), "active");
        assertPatchRefused(id, Map.of("active", "yes"), "active");
        assertPatchRefused(id, Map.of("active", "true"), "active");
        assertPatchRefused(id, Map.of("active", 1), "active");
        assertThat(existing.getActive()).isTrue();
    }

    @Test
    @DisplayName("#2256 - patch accepts JSON booleans for active, false included")
    void shouldAcceptABooleanActive() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000024");
        ServiceAreaEntity existing = stubExistingArea(id);
        when(serviceAreaRepository.saveAndFlush(existing)).thenReturn(existing);

        assertThat(service.patch(id.toString(), Map.of("active", false)).getActive())
                .isFalse();
        assertThat(service.patch(id.toString(), Map.of("active", true)).getActive())
                .isTrue();
    }

    @Test
    @DisplayName("#2256 - patch description: null clears it, text sets it, anything else is 400 not 500")
    void shouldHandleDescriptionTypes() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000025");
        ServiceAreaEntity existing = stubExistingArea(id);
        when(serviceAreaRepository.saveAndFlush(existing)).thenReturn(existing);

        assertThat(service.patch(id.toString(), mapOf("description", null)).getDescription())
                .isNull();
        assertThat(service.patch(id.toString(), Map.of("description", "new text"))
                        .getDescription())
                .isEqualTo("new text");

        existing.setDescription("kept");
        clearInvocations(serviceAreaRepository);
        assertPatchRefused(id, Map.of("description", 7), "description");
        assertPatchRefused(id, Map.of("description", List.of("a")), "description");
        assertPatchRefused(id, Map.of("description", "x".repeat(256)), "description");
        assertThat(existing.getDescription()).isEqualTo("kept");
    }

    @Test
    @DisplayName("#2256 - a refused key leaves every other key of the same patch unapplied")
    void shouldApplyNothingWhenOneKeyIsRefused() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000026");
        ServiceAreaEntity existing = stubExistingArea(id);

        assertPatchRefused(id, Map.of("name", "Renamed", "active", "nope"), "active");

        assertThat(existing.getName()).isEqualTo(DOWNTOWN_AREA);
    }

    @Test
    @DisplayName("#2256 - an empty patch changes nothing and is not an error")
    void shouldTreatAbsentKeysAsUnchanged() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000027");
        ServiceAreaEntity existing = stubExistingArea(id);
        when(serviceAreaRepository.saveAndFlush(existing)).thenReturn(existing);

        ServiceAreaResponse updated = service.patch(id.toString(), Map.of());

        assertThat(updated.getName()).isEqualTo(DOWNTOWN_AREA);
        assertThat(updated.getDescription()).isEqualTo("old");
        assertThat(updated.getActive()).isTrue();
    }

    @Test
    @DisplayName("#2256 - create flushes so a duplicate name surfaces here, not at commit")
    void shouldFlushOnCreate() {
        when(serviceAreaRepository.saveAndFlush(any(ServiceAreaEntity.class))).thenAnswer(call -> call.getArgument(0));

        service.create(ServiceAreaRequest.builder()
                .name(" Padded ")
                .postalCodes(List.of(entry("94107", "US")))
                .build());

        ArgumentCaptor<ServiceAreaEntity> saved = ArgumentCaptor.forClass(ServiceAreaEntity.class);
        verify(serviceAreaRepository).saveAndFlush(saved.capture());
        verify(serviceAreaRepository, never()).save(any(ServiceAreaEntity.class));
        assertThat(saved.getValue().getName()).isEqualTo("Padded");
    }

    @Test
    @DisplayName("#2256 - a blank name on create is 400 on name and nothing is saved")
    void shouldRefuseABlankNameOnCreate() {
        ServiceAreaRequest request = ServiceAreaRequest.builder()
                .name("  ")
                .postalCodes(List.of(entry("94107", "US")))
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class,
                        e -> assertThat(e.getField()).isEqualTo("name"));
        verify(serviceAreaRepository, never()).saveAndFlush(any(ServiceAreaEntity.class));
    }

    @Test
    @DisplayName("#2256 - a violation of a different constraint on service_areas is a plain conflict, not a name clash")
    void shouldNotReportOtherConstraintsAsNameTaken() {
        when(serviceAreaRepository.saveAndFlush(any(ServiceAreaEntity.class)))
                .thenThrow(constraintViolation(
                        "duplicate key value violates unique constraint \"service_areas_tenant_key\""
                                + " on table service_areas, column name mentioned in detail"));

        ServiceAreaRequest request = ServiceAreaRequest.builder()
                .name("Whatever")
                .postalCodes(List.of(entry("94107", "US")))
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("SERVICE_AREA_CONFLICT");
    }

    @Test
    @DisplayName("#2256 - replacing postal codes flushes and maps a violation instead of deferring it")
    void shouldFlushOnReplacePostalCodes() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000028");
        when(serviceAreaRepository.findById(id)).thenReturn(Optional.of(areaWith(id, "94107")));
        when(serviceAreaRepository.saveAndFlush(any(ServiceAreaEntity.class)))
                .thenThrow(constraintViolation("something else"));

        ServiceAreaPostalCodesRequest request = replacementOf("94112");
        String idText = id.toString();

        assertThatThrownBy(() -> service.replacePostalCodes(idText, request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("SERVICE_AREA_CONFLICT");
    }

    @Test
    @DisplayName("#2350 - a length overflow (Hibernate DataException) is rethrown, not rendered as a conflict")
    void shouldRethrowNonConstraintIntegrityViolation() {
        DataIntegrityViolationException overflow = new DataIntegrityViolationException(
                "could not execute statement",
                new DataException(
                        "could not execute statement",
                        new SQLException("ERROR: value too long for type character varying(255)", "22001")));
        when(serviceAreaRepository.saveAndFlush(any(ServiceAreaEntity.class))).thenThrow(overflow);

        ServiceAreaRequest request = ServiceAreaRequest.builder()
                .name("Whatever")
                .postalCodes(List.of(entry("94107", "US")))
                .build();

        assertThatThrownBy(() -> service.create(request)).isSameAs(overflow);
    }

    /** What Spring's Hibernate exception translation raises for a constraint violation. */
    private static DataIntegrityViolationException constraintViolation(String message) {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException(message, new SQLException(message, "23505"), null));
    }
}
