package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.dto.CoverageRuleRequest;
import com.positivity.location.internal.dto.MobileUnitRequest;
import com.positivity.location.internal.dto.MobileUnitResponse;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.MobileUnitCoverageRuleEntity;
import com.positivity.location.internal.entity.MobileUnitEntity;
import com.positivity.location.internal.entity.ServiceAreaEntity;
import com.positivity.location.internal.entity.TravelBufferPolicyEntity;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.InvalidFieldException;
import com.positivity.location.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.MobileUnitCoverageRuleRepository;
import com.positivity.location.internal.repository.MobileUnitRepository;
import com.positivity.location.internal.repository.ServiceAreaRepository;
import com.positivity.location.internal.repository.TravelBufferPolicyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Refusals on the mobile unit API (#2252, #2248) and the list's filters (#2253): each refusal
 * carries the status, code and field a client maps to a message, and nothing is written.
 */
@ExtendWith(MockitoExtension.class)
class MobileUnitValidationTest {

    private static final UUID UNIT_ID = UUID.fromString("019200aa-0000-7000-8000-000000000001");
    private static final UUID BASE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000b1");
    private static final UUID POLICY_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000c1");
    private static final UUID AREA_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000d1");
    private static final UUID OTHER_AREA_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000d2");

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private MobileUnitRepository mobileUnitRepository;

    @Mock
    private MobileUnitCoverageRuleRepository coverageRuleRepository;

    @Mock
    private ServiceAreaRepository serviceAreaRepository;

    @Mock
    private TravelBufferPolicyRepository travelBufferPolicyRepository;

    @Mock
    private ExtCatalogServiceReplicaRepository extCatalogServiceReplicaRepository;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private LocationFactPublisher locationFactPublisher;

    @InjectMocks
    private MobileUnitServiceImpl service;

    private static CoverageRuleRequest areaRule(UUID areaId) {
        return CoverageRuleRequest.builder()
                .serviceAreaId(areaId)
                .ruleType("SERVICE_AREA")
                .priority(1)
                .build();
    }

    private static CoverageRuleRequest tier(BigDecimal maxDistance) {
        return CoverageRuleRequest.builder()
                .serviceAreaId(AREA_ID)
                .ruleType("DISTANCE_TIER")
                .priority(1)
                .maxDistance(maxDistance)
                .build();
    }

    private static MobileUnitRequest.MobileUnitRequestBuilder inactiveUnit() {
        return MobileUnitRequest.builder().name("Van 7").baseLocationId(BASE_ID).status("INACTIVE");
    }

    private static MobileUnitEntity unit(String status) {
        MobileUnitEntity unit = MobileUnitEntity.builder()
                .id(UNIT_ID)
                .name("Van 7")
                .baseLocation(Location.builder().id(BASE_ID).build())
                .status(status)
                .travelBufferPolicyId(POLICY_ID)
                .notes("old")
                .build();
        unit.getServiceCapabilityCodes().add("OIL-CHANGE-FULL-SYNTHETIC");
        return unit;
    }

    private static void assertField(Throwable thrown, HttpStatus status, String code, String field) {
        assertThat(thrown).isInstanceOf(InvalidFieldException.class);
        InvalidFieldException e = (InvalidFieldException) thrown;
        assertThat(e.getStatusCode()).isEqualTo(status);
        assertThat(e.getCode()).isEqualTo(code);
        assertThat(e.getField()).isEqualTo(field);
    }

    private void assertNothingWritten() {
        verify(mobileUnitRepository, never()).save(any());
        verify(coverageRuleRepository, never()).deleteByMobileUnit_Id(any());
        verify(coverageRuleRepository, never()).saveAll(anyList());
        verify(locationFactPublisher, never()).mobileUnitChanged(any());
    }

    @Nested
    @DisplayName("POST /v1/mobile-units")
    class Create {

        @Test
        @DisplayName("row 5 - a blank name is 400 VALIDATION_ERROR on name")
        void blankName() {
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.createMobileUnit(inactiveUnit().name("   ").build()));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "name");
            assertNothingWritten();
        }

        @Test
        @DisplayName("row 4 - a missing baseLocationId is 400 on baseLocationId")
        void missingBaseLocation() {
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                    service.createMobileUnit(inactiveUnit().baseLocationId(null).build()));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "baseLocationId");
            assertNothingWritten();
        }

        @Test
        @DisplayName("row 4 - an unknown baseLocationId is 422 LOCATION_NOT_FOUND, not a unit with no shop")
        void unknownBaseLocation() {
            when(locationRepository.findById(BASE_ID)).thenReturn(Optional.empty());

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.createMobileUnit(inactiveUnit().build()));

            assertField(
                    thrown,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    MobileUnitServiceImpl.LOCATION_NOT_FOUND,
                    "baseLocationId");
            assertNothingWritten();
        }

        @Test
        @DisplayName("an unknown status is 400 on status rather than stored")
        void unknownStatus() {
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                    service.createMobileUnit(inactiveUnit().status("PAUSED").build()));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "status");
            assertNothingWritten();
        }

        @Test
        @DisplayName("row 3 - DISTANCE_TIER rules out of order are 400 on coverageRules")
        void tiersOutOfOrder() {
            MobileUnitRequest request = inactiveUnit()
                    .coverageRules(List.of(tier(BigDecimal.valueOf(25)), tier(BigDecimal.valueOf(10)), tier(null)))
                    .build();

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.createMobileUnit(request));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "coverageRules");
            assertNothingWritten();
        }

        @Test
        @DisplayName("#2248 - an unknown ruleType (the old schema example INCLUDE) is 400 on that rule")
        void unknownRuleType() {
            MobileUnitRequest request = inactiveUnit()
                    .coverageRules(List.of(
                            areaRule(AREA_ID),
                            areaRule(AREA_ID).toBuilder().ruleType("INCLUDE").build()))
                    .build();

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.createMobileUnit(request));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "coverageRules[1].ruleType");
            assertNothingWritten();
        }

        @Test
        @DisplayName("#2248 - an unknown serviceAreaId is 422 SERVICE_AREA_NOT_FOUND on that rule")
        void unknownServiceArea() {
            when(locationRepository.findById(BASE_ID))
                    .thenReturn(Optional.of(Location.builder().id(BASE_ID).build()));
            when(serviceAreaRepository.findAllById(any()))
                    .thenReturn(List.of(ServiceAreaEntity.builder().id(AREA_ID).build()));
            MobileUnitRequest request = inactiveUnit()
                    .coverageRules(List.of(areaRule(AREA_ID), areaRule(OTHER_AREA_ID)))
                    .build();

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.createMobileUnit(request));

            assertField(
                    thrown,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    MobileUnitServiceImpl.SERVICE_AREA_NOT_FOUND,
                    "coverageRules[1].serviceAreaId");
            assertNothingWritten();
        }

        @Test
        @DisplayName("a malformed travelBufferPolicyId on the map path is 400, not silently dropped")
        void malformedPolicyIdOnMapPath() {
            Map<String, Object> request = Map.of(
                    "name", "Van 7",
                    "baseLocationId", BASE_ID.toString(),
                    "travelBufferPolicyId", "not-a-uuid");

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.createMobileUnit(request));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "travelBufferPolicyId");
            assertNothingWritten();
        }
    }

    @Nested
    @DisplayName("PATCH /v1/mobile-units/{id}")
    class Patch {

        @Test
        @DisplayName("row 7 - an explicit null status is 400, never stored as \"NULL\"")
        void nullStatus() {
            MobileUnitEntity existing = unit("INACTIVE");
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(existing));
            Map<String, Object> patch = new HashMap<>();
            patch.put("status", null);

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.patch(UNIT_ID, patch));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "status");
            assertThat(existing.getStatus()).isEqualTo("INACTIVE");
            assertNothingWritten();
        }

        @Test
        @DisplayName("row 7 - any status but ACTIVE / INACTIVE is 400")
        void unknownStatus() {
            MobileUnitEntity existing = unit("INACTIVE");
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(existing));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.patch(UNIT_ID, Map.of("status", "retired")));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "status");
            assertNothingWritten();
        }

        @Test
        @DisplayName("a lower-case known status is accepted and stored upper-case")
        void lowerCaseStatusNormalized() {
            MobileUnitEntity existing = unit("ACTIVE");
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(existing));
            when(mobileUnitRepository.save(existing)).thenReturn(existing);

            MobileUnitResponse patched = service.patch(UNIT_ID, Map.of("status", " inactive "));

            assertThat(patched.getStatus()).isEqualTo("INACTIVE");
            verify(locationFactPublisher).mobileUnitChanged(existing);
        }

        @Test
        @DisplayName("row 8 - an unknown travelBufferPolicyId is 422 TRAVEL_BUFFER_POLICY_NOT_FOUND, as on create")
        void unknownPolicy() {
            UUID unknown = UUID.fromString("019200aa-0000-7000-8000-0000000000c9");
            MobileUnitEntity existing = unit("INACTIVE");
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(existing));
            when(travelBufferPolicyRepository.findById(unknown)).thenReturn(Optional.empty());

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.patch(UNIT_ID, Map.of("travelBufferPolicyId", unknown.toString())));

            assertField(
                    thrown,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    MobileUnitServiceImpl.TRAVEL_BUFFER_POLICY_NOT_FOUND,
                    "travelBufferPolicyId");
            assertThat(existing.getTravelBufferPolicyId()).isEqualTo(POLICY_ID);
            assertNothingWritten();
        }

        @Test
        @DisplayName("a travelBufferPolicyId that is not a UUID is 400 rather than clearing the policy")
        void malformedPolicy() {
            MobileUnitEntity existing = unit("INACTIVE");
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(existing));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.patch(UNIT_ID, Map.of("travelBufferPolicyId", "policy-7")));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "travelBufferPolicyId");
            assertThat(existing.getTravelBufferPolicyId()).isEqualTo(POLICY_ID);
            assertNothingWritten();
        }

        @Test
        @DisplayName("an existing travelBufferPolicyId is accepted")
        void knownPolicy() {
            UUID other = UUID.fromString("019200aa-0000-7000-8000-0000000000c2");
            MobileUnitEntity existing = unit("INACTIVE");
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(existing));
            when(travelBufferPolicyRepository.findById(other))
                    .thenReturn(Optional.of(
                            TravelBufferPolicyEntity.builder().id(other).build()));
            when(mobileUnitRepository.save(existing)).thenReturn(existing);

            MobileUnitResponse patched = service.patch(UNIT_ID, Map.of("travelBufferPolicyId", other.toString()));

            assertThat(patched.getTravelBufferPolicyId()).isEqualTo(other);
        }

        @Test
        @DisplayName("a blank name is 400 on name")
        void blankName() {
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(unit("INACTIVE")));

            Throwable thrown =
                    org.assertj.core.api.Assertions.catchThrowable(() -> service.patch(UNIT_ID, Map.of("name", " ")));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "name");
            assertNothingWritten();
        }

        @Test
        @DisplayName("row 6 - renaming onto another unit's name at the base location is 409 MOBILE_UNIT_NAME_TAKEN")
        void renameCollision() {
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(unit("INACTIVE")));
            when(mobileUnitRepository.existsByBaseLocationIdAndNameIgnoreCaseAndIdNot(BASE_ID, "Van 8", UNIT_ID))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.patch(UNIT_ID, Map.of("name", "Van 8")))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessage("MOBILE_UNIT_NAME_TAKEN");
            assertNothingWritten();
        }

        @Test
        @DisplayName("serviceCapabilityCodes that is not an array is 400 rather than clearing the claim")
        void capabilityCodesNotAnArray() {
            MobileUnitEntity existing = unit("INACTIVE");
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(existing));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.patch(UNIT_ID, Map.of("serviceCapabilityCodes", "OIL-CHANGE-FULL-SYNTHETIC")));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "serviceCapabilityCodes");
            assertThat(existing.getServiceCapabilityCodes()).containsExactly("OIL-CHANGE-FULL-SYNTHETIC");
            assertNothingWritten();
        }
    }

    @Nested
    @DisplayName("PUT /v1/mobile-units/{id}/coverage-rules (#2248)")
    class ReplaceCoverage {

        @Test
        @DisplayName("an empty set on an ACTIVE unit is 422 and the existing rules are untouched")
        void emptySetOnActiveUnit() {
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(unit("ACTIVE")));

            assertThatThrownBy(() -> service.replaceCoverageRules(UNIT_ID, List.of()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining(MobileUnitServiceImpl.ACTIVE_UNIT_INCOMPLETE)
                    .extracting("statusCode")
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertNothingWritten();
        }

        @Test
        @DisplayName("an empty set on an INACTIVE unit clears its coverage")
        void emptySetOnInactiveUnit() {
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(unit("INACTIVE")));
            when(coverageRuleRepository.saveAll(anyList())).thenReturn(List.of());

            assertThat(service.replaceCoverageRules(UNIT_ID, List.of())).isEmpty();

            verify(coverageRuleRepository).deleteByMobileUnit_Id(UNIT_ID);
        }

        @Test
        @DisplayName("DISTANCE_TIER rules out of order are 400 on rules")
        void tiersOutOfOrder() {
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(unit("ACTIVE")));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.replaceCoverageRules(UNIT_ID, List.of(tier(null), tier(BigDecimal.valueOf(10)))));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "rules");
            assertNothingWritten();
        }

        @Test
        @DisplayName("two catch-all tiers are 400 on rules")
        void twoCatchAlls() {
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(unit("ACTIVE")));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.replaceCoverageRules(UNIT_ID, List.of(tier(null), tier(null))));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "rules");
            assertNothingWritten();
        }

        @Test
        @DisplayName("an unknown serviceAreaId is 422 SERVICE_AREA_NOT_FOUND instead of a rule with no area")
        void unknownServiceArea() {
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(unit("ACTIVE")));
            when(serviceAreaRepository.findAllById(any())).thenReturn(List.of());

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.replaceCoverageRules(UNIT_ID, List.of(areaRule(AREA_ID))));

            assertField(
                    thrown,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    MobileUnitServiceImpl.SERVICE_AREA_NOT_FOUND,
                    "rules[0].serviceAreaId");
            assertNothingWritten();
        }

        @Test
        @DisplayName("an unknown ruleType is 400 on rules[i].ruleType")
        void unknownRuleType() {
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(unit("ACTIVE")));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.replaceCoverageRules(
                    UNIT_ID,
                    List.of(areaRule(AREA_ID).toBuilder().ruleType("ZIP").build())));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "rules[0].ruleType");
            assertNothingWritten();
        }

        @Test
        @DisplayName("a rule with no serviceAreaId is 400 on rules[i].serviceAreaId")
        void missingServiceArea() {
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(unit("ACTIVE")));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.replaceCoverageRules(UNIT_ID, List.of(areaRule(null))));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "rules[0].serviceAreaId");
            assertNothingWritten();
        }

        @Test
        @DisplayName("validTo before validFrom is 400 on rules[i].validTo")
        void windowEndsBeforeItStarts() {
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(unit("ACTIVE")));
            CoverageRuleRequest rule = areaRule(AREA_ID).toBuilder()
                    .validFrom(LocalDate.of(2026, 10, 1))
                    .validTo(LocalDate.of(2026, 9, 1))
                    .build();

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.replaceCoverageRules(UNIT_ID, List.of(rule)));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "rules[0].validTo");
            assertNothingWritten();
        }

        @Test
        @DisplayName("a value of the wrong type on the map path is 400 naming it, not read as absent")
        void malformedPriorityOnMapPath() {
            List<Map<String, Object>> rules = List.of(Map.of(
                    "serviceAreaId", AREA_ID.toString(),
                    "ruleType", "SERVICE_AREA",
                    "priority", "first"));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.replaceCoverageRules(UNIT_ID.toString(), rules));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "rules[0].priority");
            assertNothingWritten();
        }

        @Test
        @DisplayName("a valid mixed set is saved with ruleType upper-cased, tiers checked on their own")
        void validMixedSet() {
            MobileUnitEntity unit = unit("ACTIVE");
            when(mobileUnitRepository.findById(UNIT_ID)).thenReturn(Optional.of(unit));
            when(serviceAreaRepository.findAllById(any()))
                    .thenReturn(List.of(
                            ServiceAreaEntity.builder().id(AREA_ID).build(),
                            ServiceAreaEntity.builder().id(OTHER_AREA_ID).build()));
            when(coverageRuleRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

            var saved = service.replaceCoverageRules(
                    UNIT_ID,
                    List.of(
                            areaRule(OTHER_AREA_ID).toBuilder()
                                    .ruleType("service_area")
                                    .build(),
                            tier(BigDecimal.valueOf(15)),
                            tier(null)));

            assertThat(saved).extracting("ruleType").containsExactly("SERVICE_AREA", "DISTANCE_TIER", "DISTANCE_TIER");
            assertThat(saved).extracting("serviceAreaId").containsExactly(OTHER_AREA_ID, AREA_ID, AREA_ID);
            verify(coverageRuleRepository).deleteByMobileUnit_Id(UNIT_ID);
        }
    }

    @Nested
    @DisplayName("GET /v1/mobile-units filters (#2253)")
    class ListFilters {

        private final MobileUnitEntity van = unit("ACTIVE");

        @Test
        @DisplayName("baseLocationId narrows to that location's units")
        void byBaseLocation() {
            when(mobileUnitRepository.findByBaseLocation_Id(eq(BASE_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(van)));

            var page = service.list(0, 20, BASE_ID, null, false);

            assertThat(page.getContent()).extracting("id").containsExactly(UNIT_ID);
            assertThat(page.getContent().get(0).getCoverageRules()).isNull();
            verify(mobileUnitRepository, never()).findAll(any(Pageable.class));
            verifyNoInteractions(coverageRuleRepository);
        }

        @Test
        @DisplayName("baseLocationId and status narrow together; status matches any case")
        void byBaseLocationAndStatus() {
            when(mobileUnitRepository.findByBaseLocation_IdAndStatus(eq(BASE_ID), eq("ACTIVE"), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(van)));

            assertThat(service.list(0, 20, BASE_ID, "active", false).getContent())
                    .hasSize(1);
        }

        @Test
        @DisplayName("status alone narrows every location's units")
        void byStatus() {
            when(mobileUnitRepository.findByStatus(eq("INACTIVE"), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            assertThat(service.list(0, 20, null, "INACTIVE", false).getContent())
                    .isEmpty();
        }

        @Test
        @DisplayName("an unknown status is 400 on status")
        void unknownStatus() {
            Throwable thrown =
                    org.assertj.core.api.Assertions.catchThrowable(() -> service.list(0, 20, null, "PAUSED", false));

            assertField(thrown, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "status");
        }

        @Test
        @DisplayName("include=coverageRules attaches each unit's rules, read for the page in one query")
        void includeCoverageRules() {
            MobileUnitEntity bare = MobileUnitEntity.builder()
                    .id(UUID.fromString("019200aa-0000-7000-8000-000000000002"))
                    .name("Van 8")
                    .status("INACTIVE")
                    .build();
            when(mobileUnitRepository.findByBaseLocation_Id(eq(BASE_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(van, bare)));
            when(coverageRuleRepository.findByMobileUnit_IdInOrderByPriorityAsc(List.of(UNIT_ID, bare.getId())))
                    .thenReturn(List.of(MobileUnitCoverageRuleEntity.builder()
                            .id(UUID.fromString("019200aa-0000-7000-8000-0000000000e1"))
                            .mobileUnit(van)
                            .serviceArea(ServiceAreaEntity.builder().id(AREA_ID).build())
                            .ruleType("SERVICE_AREA")
                            .priority(1)
                            .build()));

            var page = service.list(0, 20, BASE_ID, null, true);

            assertThat(page.getContent().get(0).getCoverageRules())
                    .singleElement()
                    .satisfies(rule -> assertThat(rule.getServiceAreaId()).isEqualTo(AREA_ID));
            assertThat(page.getContent().get(1).getCoverageRules()).isEmpty();
        }
    }
}
