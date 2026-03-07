package com.positivity.location.service;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.dto.TravelBufferPolicyRequest;
import com.positivity.location.internal.dto.TravelBufferPolicyResponse;
import com.positivity.location.internal.entity.TravelBufferPolicyEntity;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.TravelBufferPolicyRepository;
import com.positivity.location.internal.service.TravelBufferPolicyServiceImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Service-layer RED tests for Travel Buffer Policy behavior.
 *
 * These tests define expected CRUD and validation semantics for Story #76,
 * including supported buffer types and non-negative buffer value constraints.
 *
 * Issue: #76
 */
@ExtendWith(MockitoExtension.class)
class TravelBufferPolicyServiceTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Spy
        Clock clock = TEST_CLOCK;

        @Mock
        private TravelBufferPolicyRepository repository;

        @InjectMocks
        private TravelBufferPolicyServiceImpl service;

        @Test
        @DisplayName("#76 - create policy persists name, type and value")
        void shouldCreateTravelBufferPolicy() {
                TravelBufferPolicyEntity persisted = TravelBufferPolicyEntity.builder()
                                .id(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .name("Standard Buffer")
                                .bufferType("FLAT_MINUTES")
                                .bufferValue(new BigDecimal("15"))
                                .notes("default policy")
                                .createdAt(Instant.now(TEST_CLOCK))
                                .updatedAt(Instant.now(TEST_CLOCK))
                                .build();
                when(repository.save(any(TravelBufferPolicyEntity.class))).thenReturn(persisted);

                Map<String, Object> request = Map.of(
                                "name", "Standard Buffer",
                                "bufferType", "FLAT_MINUTES",
                                "bufferValue", new BigDecimal("15"),
                                "notes", "default policy");

                TravelBufferPolicyResponse created = service.create(request);

                assertThat(created).isNotNull();
                assertThat(created.getName()).isEqualTo("Standard Buffer");
                assertThat(created.getBufferType()).isEqualTo("FLAT_MINUTES");
                assertThat(created.getBufferValue()).isEqualByComparingTo("15");
        }

        @Test
        @DisplayName("#76 - create duplicate travel buffer policy name maps to conflict code")
        void shouldMapDuplicateNameConstraintToConflictCode() {
                when(repository.save(any(TravelBufferPolicyEntity.class)))
                                .thenThrow(new DataIntegrityViolationException(
                                                "violates travel_buffer_policies_name_key"));

                Map<String, Object> request = Map.of(
                                "name", "Standard Buffer",
                                "bufferType", "FLAT_MINUTES",
                                "bufferValue", new BigDecimal("15"),
                                "notes", "default policy");

                assertThatThrownBy(() -> service.create(request))
                                .isInstanceOf(DuplicateResourceException.class)
                                .hasMessage("TRAVEL_BUFFER_POLICY_NAME_TAKEN");
        }

        @Test
        @DisplayName("#76 - create policy rejects unsupported bufferType")
        void shouldRejectInvalidBufferType() {
                Map<String, Object> request = Map.of(
                                "name", "Invalid",
                                "bufferType", "INVALID_TYPE",
                                "bufferValue", new BigDecimal("5"));

                assertThatThrownBy(() -> service.create(request))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("bufferType is invalid");
        }

        @Test
        @DisplayName("#76 - create policy rejects missing required bufferType")
        void shouldRejectMissingRequiredBufferType() {
                Map<String, Object> request = Map.of(
                                "name", "Missing Type",
                                "bufferValue", new BigDecimal("8"));

                assertThatThrownBy(() -> service.create(request))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("bufferType is invalid");
        }

        @Test
        @DisplayName("#76 - patch policy updates notes and bufferValue")
        void shouldPatchTravelBufferPolicy() {
                java.util.UUID policyId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
                TravelBufferPolicyEntity existing = TravelBufferPolicyEntity.builder()
                                .id(policyId)
                                .name("Standard")
                                .bufferType("FLAT_MINUTES")
                                .bufferValue(new BigDecimal("15"))
                                .notes("old")
                                .build();
                when(repository.findById(policyId)).thenReturn(java.util.Optional.of(existing));
                when(repository.save(existing)).thenReturn(existing);

                Map<String, Object> patch = Map.of(
                                "bufferValue", new BigDecimal("20"),
                                "notes", "rush-hour policy");

                TravelBufferPolicyResponse updated = service.patch(policyId.toString(), patch);

                assertThat(updated).isNotNull();
                assertThat(updated.getBufferValue()).isEqualByComparingTo("20");
                assertThat(updated.getNotes()).isEqualTo("rush-hour policy");
        }

        @Test
        @DisplayName("#76 - create policy rejects negative bufferValue")
        void shouldRejectNegativeBufferValue() {
                Map<String, Object> request = Map.of(
                                "name", "Negative Buffer",
                                "bufferType", "PERCENTAGE_OF_TRAVEL",
                                "bufferValue", new BigDecimal("-1"));

                assertThatThrownBy(() -> service.create(request))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("bufferValue must be non-negative");
        }

        @Test
        @DisplayName("#76 - patch invalid bufferType on existing policy throws")
        void shouldRejectPatchWithInvalidBufferType() {
                java.util.UUID policyId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
                String policyIdValue = policyId.toString();
                Map<String, Object> patch = Map.of("bufferType", "BAD");
                TravelBufferPolicyEntity existing = TravelBufferPolicyEntity.builder()
                                .id(policyId)
                                .name("Standard")
                                .bufferType("FLAT_MINUTES")
                                .bufferValue(new BigDecimal("15"))
                                .build();
                when(repository.findById(policyId)).thenReturn(java.util.Optional.of(existing));

                assertThatThrownBy(() -> service.patch(policyIdValue, patch))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("bufferType is invalid");
                verify(repository, never()).save(any(TravelBufferPolicyEntity.class));
        }

        @Test
        @DisplayName("#76 - patch invalid id returns bad request")
        void shouldRejectPatchWhenIdIsInvalid() {
                assertThatThrownBy(() -> service.patch("not-a-uuid", Map.of(
                                "bufferType", "FLAT_MINUTES",
                                "bufferValue", 12)))
                                .isInstanceOf(ResponseStatusException.class)
                                .hasMessageContaining("400 BAD_REQUEST");
                verify(repository, never()).save(any(TravelBufferPolicyEntity.class));
        }

        @Test
        @DisplayName("#76 - patch missing policy throws not found")
        void shouldThrowWhenPatchTargetMissing() {
                java.util.UUID policyId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
                when(repository.findById(policyId)).thenReturn(java.util.Optional.empty());

                assertThatThrownBy(() -> service.patch(policyId.toString(), Map.of(
                                "bufferType", "FLAT_MINUTES",
                                "bufferValue", 12)))
                                .isInstanceOf(ResourceNotFoundException.class)
                                .hasMessage("Travel buffer policy not found");
                verify(repository, never()).save(any(TravelBufferPolicyEntity.class));
        }

        @Test
        @DisplayName("#76 - list returns mapped policies")
        void shouldListPolicies() {
                TravelBufferPolicyEntity first = TravelBufferPolicyEntity.builder()
                                .id(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .name("Standard")
                                .bufferType("FLAT_MINUTES")
                                .bufferValue(new BigDecimal("10"))
                                .build();
                TravelBufferPolicyEntity second = TravelBufferPolicyEntity.builder()
                                .id(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .name("Distance")
                                .bufferType("DISTANCE_MULTIPLIER")
                                .bufferValue(new BigDecimal("1.5"))
                                .build();
                when(repository.findAll()).thenReturn(List.of(first, second));

                List<TravelBufferPolicyResponse> result = service.list();

                assertThat(result).hasSize(2);
                assertThat(result.get(0).getName()).isEqualTo("Standard");
                assertThat(result.get(1).getBufferType()).isEqualTo("DISTANCE_MULTIPLIER");
        }

        @Test
        @DisplayName("#76 - typed create accepts null bufferValue")
        void shouldCreateTypedRequestWithNullBufferValue() {
                TravelBufferPolicyRequest request = TravelBufferPolicyRequest.builder()
                                .name("No Value")
                                .bufferType("FLAT_MINUTES")
                                .bufferValue(null)
                                .build();
                TravelBufferPolicyEntity persisted = TravelBufferPolicyEntity.builder()
                                .id(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .name("No Value")
                                .bufferType("FLAT_MINUTES")
                                .bufferValue(null)
                                .build();
                when(repository.save(any(TravelBufferPolicyEntity.class))).thenReturn(persisted);

                TravelBufferPolicyResponse created = service.create(request);

                assertThat(created.getName()).isEqualTo("No Value");
                assertThat(created.getBufferValue()).isNull();
        }
}
