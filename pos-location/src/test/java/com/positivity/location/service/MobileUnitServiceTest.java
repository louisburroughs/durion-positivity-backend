package com.positivity.location.service;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.dto.CoverageRuleRequest;
import com.positivity.location.internal.dto.CoverageRuleResponse;
import com.positivity.location.internal.dto.MobileUnitRequest;
import com.positivity.location.internal.dto.MobileUnitResponse;
import com.positivity.location.internal.entity.MobileUnitCoverageRuleEntity;
import com.positivity.location.internal.entity.MobileUnitEntity;
import com.positivity.location.internal.entity.ServiceAreaEntity;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.entity.ServiceLocationCapabilityEntity;
import com.positivity.location.internal.entity.TravelBufferPolicyEntity;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.repository.MobileUnitCoverageRuleRepository;
import com.positivity.location.internal.repository.MobileUnitRepository;
import com.positivity.location.internal.repository.ServiceAreaRepository;
import com.positivity.location.internal.repository.ServiceLocationCapabilityRepository;
import com.positivity.location.internal.repository.TravelBufferPolicyRepository;
import com.positivity.location.internal.service.MobileUnitServiceImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Unit tests for {@link MobileUnitServiceImpl}.
 *
 * Issue: #76
 */
@ExtendWith(MockitoExtension.class)
class MobileUnitServiceTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Spy
        Clock clock = TEST_CLOCK;

        @Mock
        private MobileUnitRepository mobileUnitRepository;

        @Mock
        private MobileUnitCoverageRuleRepository coverageRuleRepository;

        @Mock
        private ServiceAreaRepository serviceAreaRepository;

        @Mock
        private TravelBufferPolicyRepository travelBufferPolicyRepository;

        @Mock
        private ServiceLocationCapabilityRepository serviceLocationCapabilityRepository;

        @InjectMocks
        private MobileUnitServiceImpl service;

        @Test
        @DisplayName("#76 - ACTIVE mobile unit requires policy, capability, and active coverage")
        void shouldRejectActivationWhenRequiredDependenciesAreMissing() {
                Map<String, Object> request = Map.of(
                                "name", "Unit-1",
                                "baseLocationId", UUID.fromString("00000000-0000-0000-0000-000000000001").toString(),
                                "status", "ACTIVE",
                                "capabilityIds", List.of(),
                                "coverageRules", List.of());

                assertThatThrownBy(() -> service.createMobileUnit(request))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("ACTIVE mobile unit requires travelBufferPolicyId, capabilityIds, and coverageRules");
        }

        @Test
        @DisplayName("#76 - unknown travel buffer policy rejects create")
        void shouldRejectUnknownTravelBufferPolicy() {
                UUID policyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                when(travelBufferPolicyRepository.findById(policyId)).thenReturn(java.util.Optional.empty());

                MobileUnitRequest request = MobileUnitRequest.builder()
                                .name("Unit-1")
                                .status("ACTIVE")
                                .baseLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .travelBufferPolicyId(policyId)
                                .capabilityIds(List.of("cap-ok"))
                                .coverageRules(List
                                                .of(CoverageRuleRequest.builder().ruleType("ZIP").priority(1).build()))
                                .build();

                assertThatThrownBy(() -> service.createMobileUnit(request))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("Unknown travelBufferPolicyId");
        }

        @Test
        @DisplayName("#76 - duplicate name from repository check returns conflict")
        void shouldRejectDuplicateNameFromRepository() {
                UUID baseLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                when(mobileUnitRepository.existsByBaseLocationIdAndNameIgnoreCase(baseLocationId, "NorthVan"))
                                .thenReturn(true);

                MobileUnitRequest request = MobileUnitRequest.builder()
                                .name("NorthVan")
                                .baseLocationId(baseLocationId)
                                .status("INACTIVE")
                                .build();

                assertThatThrownBy(() -> service.createMobileUnit(request))
                                .isInstanceOf(DuplicateResourceException.class)
                                .hasMessage("MOBILE_UNIT_NAME_TAKEN");
        }

        @Test
        @DisplayName("#76 - duplicate name from db constraint maps to conflict")
        void shouldMapDataIntegrityViolationToDuplicateResourceConflict() {
                UUID baseLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                when(mobileUnitRepository.existsByBaseLocationIdAndNameIgnoreCase(baseLocationId, "NorthVan"))
                                .thenReturn(false);
                when(mobileUnitRepository.save(any(MobileUnitEntity.class)))
                                .thenThrow(new DataIntegrityViolationException(
                                                "violates uq_mobile_unit_base_location_lower_name"));

                MobileUnitRequest request = MobileUnitRequest.builder()
                                .name("NorthVan")
                                .baseLocationId(baseLocationId)
                                .status("INACTIVE")
                                .build();

                assertThatThrownBy(() -> service.createMobileUnit(request))
                                .isInstanceOf(DuplicateResourceException.class)
                                .hasMessage("MOBILE_UNIT_NAME_TAKEN");
        }

        @Test
        @DisplayName("#76 - invalid capability IDs are listed in validation error")
        void shouldListInvalidCapabilityIdsWhenCatalogValidationFails() {
                ServiceLocationCapabilityEntity existing = ServiceLocationCapabilityEntity.builder()
                                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .code("CAP-OK")
                                .name("Cap ok")
                                .active(true)
                                .build();
                when(serviceLocationCapabilityRepository.findByCodeIn(anyList())).thenReturn(List.of(existing));
                List<String> capabilityIds = List.of("cap-ok", "cap-missing-1", "cap-missing-2");

                assertThatThrownBy(() -> service.validateCapabilityIds(capabilityIds))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("Invalid capabilityIds: CAP-MISSING-1, CAP-MISSING-2");
        }

        @Test
        @DisplayName("#76 - DISTANCE_TIER requires strictly ascending maxDistance and null catch-all")
        void shouldRejectInvalidDistanceTierSequence() {
                List<Map<String, Object>> invalidTiers = List.of(
                                Map.of("maxDistance", 25),
                                Map.of("maxDistance", 20),
                                Map.of("maxDistance", 50));

                assertThatThrownBy(() -> service.validateDistanceTiers(invalidTiers))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("Distance tiers must be strictly ascending and end with null catch-all");
        }

        @Test
        @DisplayName("#76 - create happy path returns DTO and persists coverage rules")
        void shouldCreateMobileUnitAndPersistCoverageRules() {
                UUID policyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID baseLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID savedId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID serviceAreaId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                when(travelBufferPolicyRepository.findById(policyId)).thenReturn(
                                java.util.Optional.of(TravelBufferPolicyEntity.builder().id(policyId).name("Standard")
                                                .build()));
                when(mobileUnitRepository.existsByBaseLocationIdAndNameIgnoreCase(baseLocationId, "Unit-1"))
                                .thenReturn(false);

                MobileUnitEntity savedEntity = MobileUnitEntity.builder()
                                .id(savedId)
                                .name("Unit-1")
                                .baseLocationId(baseLocationId)
                                .status("ACTIVE")
                                .travelBufferPolicyId(policyId)
                                .notes("ready")
                                .createdAt(Instant.now(TEST_CLOCK))
                                .updatedAt(Instant.now(TEST_CLOCK))
                                .build();
                when(mobileUnitRepository.save(any(MobileUnitEntity.class))).thenReturn(savedEntity);
                when(mobileUnitRepository.findById(savedId)).thenReturn(java.util.Optional.of(savedEntity));
                when(serviceLocationCapabilityRepository.findAllById(any())).thenReturn(List.of(
                                ServiceLocationCapabilityEntity.builder()
                                                .id(UUID.fromString(requestCapabilityId()))
                                                .code("CAP-UUID")
                                                .name("Capability UUID")
                                                .active(true)
                                                .build()));

                List<MobileUnitCoverageRuleEntity> savedRules = List.of(
                                MobileUnitCoverageRuleEntity.builder()
                                                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                                .mobileUnit(savedEntity)
                                                .serviceArea(ServiceAreaEntity.builder().id(serviceAreaId).name("North")
                                                                .build())
                                                .ruleType("ZIP")
                                                .priority(1)
                                                .build(),
                                MobileUnitCoverageRuleEntity.builder()
                                                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                                .mobileUnit(savedEntity)
                                                .ruleType("DISTANCE_TIER")
                                                .priority(2)
                                                .maxDistance(BigDecimal.valueOf(25))
                                                .build());
                when(coverageRuleRepository.saveAll(anyList())).thenReturn(savedRules);

                MobileUnitRequest request = MobileUnitRequest.builder()
                                .name("Unit-1")
                                .baseLocationId(baseLocationId)
                                .status("ACTIVE")
                                .travelBufferPolicyId(policyId)
                                .notes("ready")
                                .capabilityIds(List.of(requestCapabilityId()))
                                .coverageRules(List.of(
                                                CoverageRuleRequest.builder()
                                                                .ruleType("DISTANCE_TIER")
                                                                .priority(1)
                                                                .maxDistance(BigDecimal.valueOf(25))
                                                                .build(),
                                                CoverageRuleRequest.builder()
                                                                .ruleType("DISTANCE_TIER")
                                                                .priority(2)
                                                                .maxDistance(null)
                                                                .build()))
                                .build();

                MobileUnitResponse response = service.createMobileUnit(request);

                assertThat(response).isNotNull();
                assertThat(response.getId()).isEqualTo(savedId);
                assertThat(response.getName()).isEqualTo("Unit-1");
                verify(coverageRuleRepository).deleteByMobileUnit_Id(savedId);
                verify(coverageRuleRepository).saveAll(anyList());
        }

        private static String requestCapabilityId() {
                return "11111111-1111-1111-1111-111111111111";
        }

        @Test
        @DisplayName("#76 - happy path list returns mapped items")
        void shouldListMobileUnits() {
                UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                PageRequest pageable = PageRequest.of(0, 10);
                when(mobileUnitRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(
                                MobileUnitEntity.builder().id(firstId).name("A").status("INACTIVE").build(),
                                MobileUnitEntity.builder().id(secondId).name("B").status("ACTIVE").build()),
                                pageable, 2));

                var page = service.list(0, 10);

                assertThat(page.getContent()).hasSize(2);
                assertThat(page.getContent().get(0).getId()).isEqualTo(firstId);
                assertThat(page.getContent().get(1).getId()).isEqualTo(secondId);
        }

        @Test
        @DisplayName("#76 - get by ID found returns DTO")
        void shouldReturnMobileUnitWhenFoundById() {
                UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
                when(mobileUnitRepository.findById(id)).thenReturn(java.util.Optional.of(
                                MobileUnitEntity.builder().id(id).name("Unit-X").status("ACTIVE").build()));

                var found = service.getById(id);

                assertThat(found).isPresent();
                assertThat(found.get().getId()).isEqualTo(id);
                assertThat(found.get().getName()).isEqualTo("Unit-X");
        }

        @Test
        @DisplayName("#76 - get by ID not found returns empty")
        void shouldReturnEmptyWhenMobileUnitNotFoundById() {
                UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
                when(mobileUnitRepository.findById(id)).thenReturn(java.util.Optional.empty());

                assertThat(service.getById(id)).isEmpty();
        }

        @Test
        @DisplayName("#76 - patch updates existing fields and persists")
        void shouldPatchExistingMobileUnit() {
                UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
                MobileUnitEntity existing = MobileUnitEntity.builder()
                                .id(id)
                                .name("Old")
                                .status("INACTIVE")
                                .notes("old")
                                .build();
                when(mobileUnitRepository.findById(id)).thenReturn(java.util.Optional.of(existing));
                when(mobileUnitRepository.save(existing)).thenReturn(existing);

                MobileUnitResponse patched = service.patch(id, Map.of(
                                "name", "New",
                                "status", "ACTIVE",
                                "notes", "updated"));

                assertThat(patched.getName()).isEqualTo("New");
                assertThat(patched.getStatus()).isEqualTo("ACTIVE");
                assertThat(patched.getNotes()).isEqualTo("updated");
        }

        @Test
        @DisplayName("#76 - patch not found returns synthesized response")
        void shouldReturnFallbackResponseWhenPatchTargetMissing() {
                UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
                when(mobileUnitRepository.findById(id)).thenReturn(java.util.Optional.empty());

                MobileUnitResponse patched = service.patch(id, Map.of("name", "Ghost"));

                assertThat(patched.getId()).isEqualTo(id);
                assertThat(patched.getName()).isEqualTo("Ghost");
                verify(mobileUnitRepository, never()).save(any(MobileUnitEntity.class));
        }

        @Test
        @DisplayName("#76 - replace coverage rules maps saved entities to responses")
        void shouldReplaceCoverageRules() {
                UUID unitId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID areaId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                MobileUnitEntity unit = MobileUnitEntity.builder().id(unitId).name("Unit-Y").status("ACTIVE").build();
                when(mobileUnitRepository.findById(unitId)).thenReturn(java.util.Optional.of(unit));
                when(serviceAreaRepository.findById(areaId)).thenReturn(
                                java.util.Optional.of(ServiceAreaEntity.builder().id(areaId).name("Area").build()));

                MobileUnitCoverageRuleEntity persisted = MobileUnitCoverageRuleEntity.builder()
                                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .mobileUnit(unit)
                                .serviceArea(ServiceAreaEntity.builder().id(areaId).name("Area").build())
                                .ruleType("ZIP")
                                .priority(10)
                                .build();
                when(coverageRuleRepository.saveAll(anyList())).thenReturn(List.of(persisted));

                List<CoverageRuleResponse> responses = service.replaceCoverageRules(unitId, List.of(
                                CoverageRuleRequest.builder().serviceAreaId(areaId).ruleType("ZIP").priority(10)
                                                .build()));

                assertThat(responses).hasSize(1);
                assertThat(responses.get(0).getMobileUnitId()).isEqualTo(unitId);
                assertThat(responses.get(0).getServiceAreaId()).isEqualTo(areaId);
        }

        @Test
        @DisplayName("#76 - replace coverage rules missing mobile unit throws not found")
        void shouldThrowWhenReplacingCoverageRulesForMissingMobileUnit() {
                UUID unitId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                when(mobileUnitRepository.findById(unitId)).thenReturn(java.util.Optional.empty());

                assertThatThrownBy(() -> service.replaceCoverageRules(unitId, List.of()))
                                .isInstanceOf(ResourceNotFoundException.class)
                                .hasMessage("Mobile unit not found");
                verify(coverageRuleRepository, never()).deleteByMobileUnit_Id(unitId);
                verify(coverageRuleRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("#76 - get coverage rules returns ordered mapped rules")
        void shouldGetCoverageRules() {
                UUID unitId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                MobileUnitEntity unit = MobileUnitEntity.builder().id(unitId).name("Unit").status("ACTIVE").build();
                MobileUnitCoverageRuleEntity first = MobileUnitCoverageRuleEntity.builder()
                                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .mobileUnit(unit)
                                .ruleType("ZIP")
                                .priority(1)
                                .build();
                when(coverageRuleRepository.findByMobileUnit_IdOrderByPriorityAsc(unitId)).thenReturn(List.of(first));

                List<CoverageRuleResponse> result = service.getCoverageRules(unitId);

                assertThat(result).hasSize(1);
                assertThat(result.get(0).getRuleType()).isEqualTo("ZIP");
                assertThat(result.get(0).getPriority()).isEqualTo(1);
        }

        @Test
        @DisplayName("#76 - eligible mobile units include only active and deduplicate by unit")
        void shouldFindEligibleMobileUnitsWithActiveDeduplication() {
                UUID activeId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID inactiveId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Instant now = Instant.now(TEST_CLOCK);

                MobileUnitEntity activeUnit = MobileUnitEntity.builder()
                                .id(activeId)
                                .name("Active")
                                .status("ACTIVE")
                                .baseLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .build();
                MobileUnitEntity inactiveUnit = MobileUnitEntity.builder()
                                .id(inactiveId)
                                .name("Inactive")
                                .status("INACTIVE")
                                .baseLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .build();

                MobileUnitCoverageRuleEntity firstForActive = MobileUnitCoverageRuleEntity.builder()
                                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .mobileUnit(activeUnit)
                                .priority(2)
                                .ruleType("ZIP")
                                .build();
                MobileUnitCoverageRuleEntity secondForSameActive = MobileUnitCoverageRuleEntity.builder()
                                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .mobileUnit(activeUnit)
                                .priority(3)
                                .ruleType("ZIP")
                                .build();
                MobileUnitCoverageRuleEntity forInactive = MobileUnitCoverageRuleEntity.builder()
                                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .mobileUnit(inactiveUnit)
                                .priority(1)
                                .ruleType("ZIP")
                                .build();

                when(coverageRuleRepository.findEligibleCoverageRules(eq("94107"), eq("US"), any(LocalDate.class)))
                                .thenReturn(List.of(firstForActive, secondForSameActive, forInactive));

                var eligible = service.findEligibleMobileUnits("94107", "US", now);

                assertThat(eligible).hasSize(1);
                assertThat(eligible.get(0).getId()).isEqualTo(activeId);
                assertThat(eligible.get(0).getPriority()).isEqualTo(2);
        }
}
