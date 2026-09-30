package com.positivity.location.internal.service;

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
import com.positivity.location.internal.exception.InvalidFieldException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.TravelBufferPolicyRepository;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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
                .bufferType("FIXED_MINUTES")
                .bufferValue(new BigDecimal("15"))
                .notes("default policy")
                .createdAt(Instant.now(TEST_CLOCK))
                .updatedAt(Instant.now(TEST_CLOCK))
                .build();
        when(repository.saveAndFlush(any(TravelBufferPolicyEntity.class))).thenReturn(persisted);

        Map<String, Object> request = Map.of(
                "name", "Standard Buffer",
                "bufferType", "FIXED_MINUTES",
                "bufferValue", new BigDecimal("15"),
                "notes", "default policy");

        TravelBufferPolicyResponse created = service.create(request);

        assertThat(created).isNotNull();
        assertThat(created.getName()).isEqualTo("Standard Buffer");
        assertThat(created.getBufferType()).isEqualTo("FIXED_MINUTES");
        assertThat(created.getBufferValue()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("#76 - create duplicate travel buffer policy name maps to conflict code")
    void shouldMapDuplicateNameConstraintToConflictCode() {
        when(repository.saveAndFlush(any(TravelBufferPolicyEntity.class)))
                .thenThrow(constraintViolation("violates travel_buffer_policies_name_key"));

        Map<String, Object> request = Map.of(
                "name", "Standard Buffer",
                "bufferType", "FIXED_MINUTES",
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

        assertThatThrownBy(() -> service.create(request)).isInstanceOfSatisfying(InvalidFieldException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(e.getField()).isEqualTo("bufferType");
            assertThat(e.getReason()).isEqualTo("bufferType must be FIXED_MINUTES or DISTANCE_TIER");
        });
    }

    @Test
    @DisplayName("#76 - create policy rejects missing required bufferType")
    void shouldRejectMissingRequiredBufferType() {
        Map<String, Object> request = Map.of("name", "Missing Type", "bufferValue", new BigDecimal("8"));

        assertThatThrownBy(() -> service.create(request)).isInstanceOfSatisfying(InvalidFieldException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(e.getField()).isEqualTo("bufferType");
            assertThat(e.getReason()).isEqualTo("bufferType must be FIXED_MINUTES or DISTANCE_TIER");
        });
    }

    @Test
    @DisplayName("#76 - patch policy updates notes and bufferValue")
    void shouldPatchTravelBufferPolicy() {
        java.util.UUID policyId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        TravelBufferPolicyEntity existing = TravelBufferPolicyEntity.builder()
                .id(policyId)
                .name("Standard")
                .bufferType("FIXED_MINUTES")
                .bufferValue(new BigDecimal("15"))
                .notes("old")
                .build();
        when(repository.findById(policyId)).thenReturn(java.util.Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        Map<String, Object> patch = Map.of("bufferValue", new BigDecimal("20"), "notes", "rush-hour policy");

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
                "bufferType", "FIXED_MINUTES",
                "bufferValue", new BigDecimal("-1"));

        assertThatThrownBy(() -> service.create(request)).isInstanceOfSatisfying(InvalidFieldException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(e.getField()).isEqualTo("bufferValue");
            assertThat(e.getReason()).isEqualTo("bufferValue must be non-negative");
        });
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
                .bufferType("FIXED_MINUTES")
                .bufferValue(new BigDecimal("15"))
                .build();
        when(repository.findById(policyId)).thenReturn(java.util.Optional.of(existing));

        assertThatThrownBy(() -> service.patch(policyIdValue, patch))
                .isInstanceOfSatisfying(InvalidFieldException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(e.getField()).isEqualTo("bufferType");
                    assertThat(e.getReason()).isEqualTo("bufferType must be FIXED_MINUTES or DISTANCE_TIER");
                });
        verify(repository, never()).saveAndFlush(any(TravelBufferPolicyEntity.class));
    }

    @Test
    @DisplayName("#76 - patch invalid id returns bad request")
    void shouldRejectPatchWhenIdIsInvalid() {
        assertThatThrownBy(() -> service.patch("not-a-uuid", Map.of("bufferType", "FIXED_MINUTES", "bufferValue", 12)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        verify(repository, never()).saveAndFlush(any(TravelBufferPolicyEntity.class));
    }

    @Test
    @DisplayName("#76 - patch missing policy throws not found")
    void shouldThrowWhenPatchTargetMissing() {
        java.util.UUID policyId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(repository.findById(policyId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() ->
                        service.patch(policyId.toString(), Map.of("bufferType", "FIXED_MINUTES", "bufferValue", 12)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Travel buffer policy not found");
        verify(repository, never()).saveAndFlush(any(TravelBufferPolicyEntity.class));
    }

    @Test
    @DisplayName("#76 - list returns mapped policies")
    void shouldListPolicies() {
        TravelBufferPolicyEntity first = TravelBufferPolicyEntity.builder()
                .id(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .name("Standard")
                .bufferType("FIXED_MINUTES")
                .bufferValue(new BigDecimal("10"))
                .build();
        TravelBufferPolicyEntity second = TravelBufferPolicyEntity.builder()
                .id(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .name("Distance")
                .bufferType("DISTANCE_TIER")
                .bufferValue(new BigDecimal("1.5"))
                .build();
        when(repository.findAll()).thenReturn(List.of(first, second));

        List<TravelBufferPolicyResponse> result = service.list();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("Standard");
        assertThat(result.get(1).getBufferType()).isEqualTo("DISTANCE_TIER");
    }

    @Test
    @DisplayName("#76 - typed create accepts null bufferValue")
    void shouldCreateTypedRequestWithNullBufferValue() {
        TravelBufferPolicyRequest request = TravelBufferPolicyRequest.builder()
                .name("No Value")
                .bufferType("FIXED_MINUTES")
                .bufferValue(null)
                .build();
        TravelBufferPolicyEntity persisted = TravelBufferPolicyEntity.builder()
                .id(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .name("No Value")
                .bufferType("FIXED_MINUTES")
                .bufferValue(null)
                .build();
        when(repository.saveAndFlush(any(TravelBufferPolicyEntity.class))).thenReturn(persisted);

        TravelBufferPolicyResponse created = service.create(request);

        assertThat(created.getName()).isEqualTo("No Value");
        assertThat(created.getBufferValue()).isNull();
    }

    @Test
    @DisplayName("#2252 row 10 - patch with a null bufferType is 400, never stored as the text \"null\"")
    void shouldRejectPatchWithNullBufferType() {
        java.util.UUID policyId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        TravelBufferPolicyEntity existing = TravelBufferPolicyEntity.builder()
                .id(policyId)
                .name("Standard")
                .bufferType("FIXED_MINUTES")
                .build();
        when(repository.findById(policyId)).thenReturn(java.util.Optional.of(existing));
        Map<String, Object> patch = new java.util.HashMap<>();
        patch.put("bufferType", null);

        assertThatThrownBy(() -> service.patch(policyId.toString(), patch))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class,
                        e -> assertThat(e.getField()).isEqualTo("bufferType"));
        assertThat(existing.getBufferType()).isEqualTo("FIXED_MINUTES");
        verify(repository, never()).saveAndFlush(any(TravelBufferPolicyEntity.class));
    }

    @Test
    @DisplayName("#2252 row 10 - patch with a non-numeric bufferValue is 400 rather than clearing the value")
    void shouldRejectPatchWithNonNumericBufferValue() {
        java.util.UUID policyId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        TravelBufferPolicyEntity existing = TravelBufferPolicyEntity.builder()
                .id(policyId)
                .name("Standard")
                .bufferType("FIXED_MINUTES")
                .bufferValue(new BigDecimal("15"))
                .build();
        when(repository.findById(policyId)).thenReturn(java.util.Optional.of(existing));

        assertThatThrownBy(() -> service.patch(policyId.toString(), Map.of("bufferValue", "lots")))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class,
                        e -> assertThat(e.getField()).isEqualTo("bufferValue"));
        assertThat(existing.getBufferValue()).isEqualByComparingTo("15");
        verify(repository, never()).saveAndFlush(any(TravelBufferPolicyEntity.class));
    }

    @Test
    @DisplayName("#2266 - DISTANCE_TIER is an accepted bufferType")
    void shouldAcceptDistanceTierBufferType() {
        TravelBufferPolicyEntity persisted = TravelBufferPolicyEntity.builder()
                .id(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .name("Tiered")
                .bufferType("DISTANCE_TIER")
                .build();
        when(repository.saveAndFlush(any(TravelBufferPolicyEntity.class))).thenReturn(persisted);

        TravelBufferPolicyResponse created = service.create(TravelBufferPolicyRequest.builder()
                .name("Tiered")
                .bufferType("DISTANCE_TIER")
                .build());

        assertThat(created.getBufferType()).isEqualTo("DISTANCE_TIER");
    }

    @Test
    @DisplayName("#2266 - the retired FLAT_MINUTES type is refused; only FIXED_MINUTES is accepted")
    void shouldRejectRetiredFlatMinutesType() {
        Map<String, Object> request =
                Map.of("name", "Old Name", "bufferType", "FLAT_MINUTES", "bufferValue", new BigDecimal("15"));

        assertThatThrownBy(() -> service.create(request)).isInstanceOfSatisfying(InvalidFieldException.class, e -> {
            assertThat(e.getField()).isEqualTo("bufferType");
            assertThat(e.getReason()).isEqualTo("bufferType must be FIXED_MINUTES or DISTANCE_TIER");
        });
    }

    @Test
    @DisplayName("#2266 - PERCENTAGE_OF_TRAVEL and DISTANCE_MULTIPLIER are refused, per DECISION-LOCATION-028")
    void shouldRejectRemovedBufferTypes() {
        for (String removedType : List.of("PERCENTAGE_OF_TRAVEL", "DISTANCE_MULTIPLIER")) {
            Map<String, Object> request =
                    Map.of("name", "Removed " + removedType, "bufferType", removedType, "bufferValue", 1);

            assertThatThrownBy(() -> service.create(request))
                    .as("bufferType %s", removedType)
                    .isInstanceOfSatisfying(
                            InvalidFieldException.class,
                            e -> assertThat(e.getField()).isEqualTo("bufferType"));
        }
    }

    @Test
    @DisplayName("#2266 - DECISION-LOCATION-015: a FIXED_MINUTES bufferValue must be a whole number of minutes")
    void shouldRejectFractionalFixedMinutesValue() {
        Map<String, Object> request =
                Map.of("name", "Fractional", "bufferType", "FIXED_MINUTES", "bufferValue", new BigDecimal("15.5"));

        assertThatThrownBy(() -> service.create(request)).isInstanceOfSatisfying(InvalidFieldException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(e.getField()).isEqualTo("bufferValue");
            assertThat(e.getReason()).isEqualTo("bufferValue must be a whole number of minutes");
        });
    }

    @Test
    @DisplayName("#2266 - a whole-number FIXED_MINUTES bufferValue, even written with trailing zeros, is accepted")
    void shouldAcceptWholeNumberFixedMinutesValueWithTrailingZeros() {
        TravelBufferPolicyEntity persisted = TravelBufferPolicyEntity.builder()
                .id(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .name("Whole")
                .bufferType("FIXED_MINUTES")
                .bufferValue(new BigDecimal("30.00"))
                .build();
        when(repository.saveAndFlush(any(TravelBufferPolicyEntity.class))).thenReturn(persisted);

        TravelBufferPolicyResponse created = service.create(TravelBufferPolicyRequest.builder()
                .name("Whole")
                .bufferType("FIXED_MINUTES")
                .bufferValue(new BigDecimal("30.00"))
                .build());

        assertThat(created.getBufferValue()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("#2266 - patch to a fractional FIXED_MINUTES bufferValue is refused")
    void shouldRejectPatchToFractionalFixedMinutesValue() {
        java.util.UUID policyId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        TravelBufferPolicyEntity existing = TravelBufferPolicyEntity.builder()
                .id(policyId)
                .name("Standard")
                .bufferType("FIXED_MINUTES")
                .bufferValue(new BigDecimal("15"))
                .build();
        when(repository.findById(policyId)).thenReturn(java.util.Optional.of(existing));

        // patch() applies a numeric bufferValue to the in-memory entity before the final
        // validateRequest pass rejects it (pre-existing shape, not changed here); what #2266 adds is
        // that nothing is persisted for a fractional FIXED_MINUTES value.
        assertThatThrownBy(() -> service.patch(policyId.toString(), Map.of("bufferValue", new BigDecimal("15.25"))))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class,
                        e -> assertThat(e.getField()).isEqualTo("bufferValue"));
        verify(repository, never()).saveAndFlush(any(TravelBufferPolicyEntity.class));
    }

    @Test
    @DisplayName("#2252 - create with a blank name is 400 on name")
    void shouldRejectCreateWithBlankName() {
        TravelBufferPolicyRequest request = TravelBufferPolicyRequest.builder()
                .name("  ")
                .bufferType("FIXED_MINUTES")
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class,
                        e -> assertThat(e.getField()).isEqualTo("name"));
        verify(repository, never()).saveAndFlush(any(TravelBufferPolicyEntity.class));
    }

    // ---------------------------------------------------------------- #2256

    @Test
    @DisplayName("#2256 - create flushes, so a duplicate name is mapped here and not lost to the commit")
    void shouldFlushOnCreate() {
        when(repository.saveAndFlush(any(TravelBufferPolicyEntity.class))).thenAnswer(call -> call.getArgument(0));

        service.create(TravelBufferPolicyRequest.builder()
                .name("  Padded ")
                .bufferType("FIXED_MINUTES")
                .bufferValue(new BigDecimal("10"))
                .build());

        verify(repository).saveAndFlush(any(TravelBufferPolicyEntity.class));
        verify(repository, never()).save(any(TravelBufferPolicyEntity.class));
    }

    @Test
    @DisplayName("#2256 - a real Postgres unique-violation message on the name key maps to NAME_TAKEN")
    void shouldMapPostgresNameKeyMessage() {
        when(repository.saveAndFlush(any(TravelBufferPolicyEntity.class)))
                .thenThrow(constraintViolation("ERROR: duplicate key value violates unique constraint"
                        + " \"travel_buffer_policies_name_key\""));

        TravelBufferPolicyRequest request = TravelBufferPolicyRequest.builder()
                .name("Standard Buffer")
                .bufferType("FIXED_MINUTES")
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("TRAVEL_BUFFER_POLICY_NAME_TAKEN");
    }

    @Test
    @DisplayName("#2256 - another constraint on the same table is TRAVEL_BUFFER_POLICY_CONFLICT, not a name clash")
    void shouldNotReportOtherConstraintsAsNameTaken() {
        when(repository.saveAndFlush(any(TravelBufferPolicyEntity.class)))
                .thenThrow(constraintViolation("violates check constraint"
                        + " \"travel_buffer_policies_buffer_type_check\" on table travel_buffer_policies, name"
                        + " Standard"));

        TravelBufferPolicyRequest request = TravelBufferPolicyRequest.builder()
                .name("Standard Buffer")
                .bufferType("FIXED_MINUTES")
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("TRAVEL_BUFFER_POLICY_CONFLICT");
    }

    @Test
    @DisplayName("#2256 - patch flushes and maps a violation instead of deferring it")
    void shouldFlushAndMapOnPatch() {
        java.util.UUID policyId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000031");
        TravelBufferPolicyEntity existing = TravelBufferPolicyEntity.builder()
                .id(policyId)
                .name("Standard Buffer")
                .bufferType("FIXED_MINUTES")
                .bufferValue(new BigDecimal("10"))
                .build();
        when(repository.findById(policyId)).thenReturn(java.util.Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenThrow(constraintViolation("some other rule"));
        String id = policyId.toString();
        Map<String, Object> patch = Map.of("bufferValue", new BigDecimal("20"));

        assertThatThrownBy(() -> service.patch(id, patch))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("TRAVEL_BUFFER_POLICY_CONFLICT");
    }

    @Test
    @DisplayName("#2350 - a length overflow (Hibernate DataException) is rethrown, not rendered as a conflict")
    void shouldRethrowNonConstraintIntegrityViolation() {
        DataIntegrityViolationException overflow = new DataIntegrityViolationException(
                "could not execute statement",
                new DataException(
                        "could not execute statement",
                        new SQLException("ERROR: value too long for type character varying(255)", "22001")));
        when(repository.saveAndFlush(any(TravelBufferPolicyEntity.class))).thenThrow(overflow);

        TravelBufferPolicyRequest request = TravelBufferPolicyRequest.builder()
                .name("Standard Buffer")
                .bufferType("FIXED_MINUTES")
                .build();

        assertThatThrownBy(() -> service.create(request)).isSameAs(overflow);
    }

    @Test
    @DisplayName("#2350 - over-long name or notes are 400 on the field before any write")
    void shouldRefuseOverLongTextBeforeWriting() {
        TravelBufferPolicyRequest longName = TravelBufferPolicyRequest.builder()
                .name("n".repeat(256))
                .bufferType("FIXED_MINUTES")
                .build();
        assertThatThrownBy(() -> service.create(longName))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class,
                        e -> assertThat(e.getField()).isEqualTo("name"));

        TravelBufferPolicyRequest longNotes = TravelBufferPolicyRequest.builder()
                .name("Standard Buffer")
                .bufferType("FIXED_MINUTES")
                .notes("x".repeat(256))
                .build();
        assertThatThrownBy(() -> service.create(longNotes))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class,
                        e -> assertThat(e.getField()).isEqualTo("notes"));

        java.util.UUID policyId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000032");
        TravelBufferPolicyEntity existing = TravelBufferPolicyEntity.builder()
                .id(policyId)
                .name("Standard Buffer")
                .bufferType("FIXED_MINUTES")
                .notes("old")
                .build();
        when(repository.findById(policyId)).thenReturn(java.util.Optional.of(existing));
        String id = policyId.toString();
        Map<String, Object> patch = Map.of("notes", "x".repeat(256));
        assertThatThrownBy(() -> service.patch(id, patch))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class,
                        e -> assertThat(e.getField()).isEqualTo("notes"));
        assertThat(existing.getNotes()).isEqualTo("old");

        verify(repository, never()).saveAndFlush(any(TravelBufferPolicyEntity.class));
    }

    /** What Spring's Hibernate exception translation raises for a constraint violation. */
    private static DataIntegrityViolationException constraintViolation(String message) {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException(message, new SQLException(message, "23505"), null));
    }
}
