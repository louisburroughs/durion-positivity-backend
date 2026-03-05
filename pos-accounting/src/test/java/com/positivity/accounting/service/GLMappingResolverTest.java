package com.positivity.accounting.service;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.service.GLMappingResolverImpl;

/**
 * Unit tests for GLMappingResolver.
 *
 * Tests dimensional matching strategy:
 * 1. Exact dimensional match (all dimensions in context match mapping)
 * 2. Dimensional fallback (progressively remove most-specific dimension)
 * 3. Category default (no dimensions)
 * 4. No mapping found (error case)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GLMappingResolver Unit Tests")
class GLMappingResolverTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Mock
        private GLMappingRepository mappingRepository;

        @InjectMocks
        private GLMappingResolverImpl resolver;

        private UUID postingCategoryId;
        private UUID mappingKeyId;
        private LocalDateTime transactionDate;

        @BeforeEach
        void setUp() {
                postingCategoryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                mappingKeyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                transactionDate = LocalDateTime.of(2026, 1, 15, 12, 0);
        }

        // ========================================
        // Exact Dimensional Matching Tests
        // ========================================

        @Nested
        @DisplayName("Exact dimensional matching")
        class ExactDimensionalMatching {

                @Test
                @DisplayName("Should resolve exact dimensional match when all dimensions match")
                void shouldResolveExactDimensionalMatch() {
                        // Arrange
                        UUID expectedAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                        Map<String, String> dimensions = Map.of(
                                        "businessUnitId", "BU-001",
                                        "locationId", "LOC-100",
                                        "departmentId", "DEPT-50",
                                        "costCenterId", "CC-200");

                        GLMapping mapping = createMapping(expectedAccountId, dimensions);

                        when(mappingRepository.findAllEffectiveMappings(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(List.of(mapping));

                        // Act
                        UUID result = resolver.resolveGLAccount(
                                        postingCategoryId, mappingKeyId, transactionDate, dimensions);

                        // Assert
                        assertThat(result).isEqualTo(expectedAccountId);
                }

                @Test
                @DisplayName("Should not match when dimensions differ")
                void shouldNotMatchWhenDimensionsDiffer() {
                        // Arrange
                        UUID mappingAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                        UUID defaultAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                        Map<String, String> mappingDimensions = Map.of(
                                        "businessUnitId", "BU-001",
                                        "locationId", "LOC-100");

                        Map<String, String> requestDimensions = Map.of(
                                        "businessUnitId", "BU-001",
                                        "locationId", "LOC-999"); // Different location

                        GLMapping dimensionedMapping = createMapping(mappingAccountId, mappingDimensions);
                        GLMapping defaultMapping = createMapping(defaultAccountId, null);

                        when(mappingRepository.findAllEffectiveMappings(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(List.of(dimensionedMapping));

                        // Category default fallback
                        when(mappingRepository.findEffectiveMapping(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(Optional.of(defaultMapping));

                        // Act
                        UUID result = resolver.resolveGLAccount(
                                        postingCategoryId, mappingKeyId, transactionDate, requestDimensions);

                        // Assert — falls through to category default
                        assertThat(result).isEqualTo(defaultAccountId);
                }

                @Test
                @DisplayName("Should skip null/empty dimension context and fall through")
                void shouldSkipNullDimensionContext() {
                        // Arrange
                        UUID defaultAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                        GLMapping defaultMapping = createMapping(defaultAccountId, null);

                        when(mappingRepository.findEffectiveMapping(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(Optional.of(defaultMapping));

                        // Act
                        UUID result = resolver.resolveGLAccount(
                                        postingCategoryId, mappingKeyId, transactionDate, null);

                        // Assert
                        assertThat(result).isEqualTo(defaultAccountId);
                }

                @Test
                @DisplayName("Should skip empty dimension context and fall through")
                void shouldSkipEmptyDimensionContext() {
                        // Arrange
                        UUID defaultAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                        GLMapping defaultMapping = createMapping(defaultAccountId, null);

                        when(mappingRepository.findEffectiveMapping(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(Optional.of(defaultMapping));

                        // Act
                        UUID result = resolver.resolveGLAccount(
                                        postingCategoryId, mappingKeyId, transactionDate, Collections.emptyMap());

                        // Assert
                        assertThat(result).isEqualTo(defaultAccountId);
                }
        }

        // ========================================
        // Dimensional Fallback Tests
        // ========================================

        @Nested
        @DisplayName("Dimensional fallback")
        class DimensionalFallback {

                @Test
                @DisplayName("Should fall back when costCenterId removed matches a mapping")
                void shouldFallBackByCostCenter() {
                        // Arrange
                        UUID fallbackAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                        // Request has 4 dimensions, mapping has 3 (no costCenterId)
                        Map<String, String> requestDimensions = Map.of(
                                        "businessUnitId", "BU-001",
                                        "locationId", "LOC-100",
                                        "departmentId", "DEPT-50",
                                        "costCenterId", "CC-200");

                        Map<String, String> mappingDimensions = Map.of(
                                        "businessUnitId", "BU-001",
                                        "locationId", "LOC-100",
                                        "departmentId", "DEPT-50");

                        GLMapping fallbackMapping = createMapping(fallbackAccountId, mappingDimensions);

                        when(mappingRepository.findAllEffectiveMappings(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(List.of(fallbackMapping));

                        // Act
                        UUID result = resolver.resolveGLAccount(
                                        postingCategoryId, mappingKeyId, transactionDate, requestDimensions);

                        // Assert — matched after removing costCenterId
                        assertThat(result).isEqualTo(fallbackAccountId);
                }

                @Test
                @DisplayName("Should fall back when costCenterId and departmentId removed matches")
                void shouldFallBackTwoLevels() {
                        // Arrange
                        UUID fallbackAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                        Map<String, String> requestDimensions = Map.of(
                                        "businessUnitId", "BU-001",
                                        "locationId", "LOC-100",
                                        "departmentId", "DEPT-50",
                                        "costCenterId", "CC-200");

                        // Mapping only has businessUnitId + locationId
                        Map<String, String> mappingDimensions = Map.of(
                                        "businessUnitId", "BU-001",
                                        "locationId", "LOC-100");

                        GLMapping fallbackMapping = createMapping(fallbackAccountId, mappingDimensions);

                        when(mappingRepository.findAllEffectiveMappings(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(List.of(fallbackMapping));

                        // Act
                        UUID result = resolver.resolveGLAccount(
                                        postingCategoryId, mappingKeyId, transactionDate, requestDimensions);

                        // Assert — matched after removing costCenterId + departmentId
                        assertThat(result).isEqualTo(fallbackAccountId);
                }

                @Test
                @DisplayName("Should fall back to businessUnitId only")
                void shouldFallBackToBusinessUnitOnly() {
                        // Arrange
                        UUID fallbackAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                        Map<String, String> requestDimensions = Map.of(
                                        "businessUnitId", "BU-001",
                                        "locationId", "LOC-100",
                                        "departmentId", "DEPT-50");

                        // Mapping only has businessUnitId
                        Map<String, String> mappingDimensions = Map.of(
                                        "businessUnitId", "BU-001");

                        GLMapping fallbackMapping = createMapping(fallbackAccountId, mappingDimensions);

                        when(mappingRepository.findAllEffectiveMappings(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(List.of(fallbackMapping));

                        // Act
                        UUID result = resolver.resolveGLAccount(
                                        postingCategoryId, mappingKeyId, transactionDate, requestDimensions);

                        // Assert
                        assertThat(result).isEqualTo(fallbackAccountId);
                }

                @Test
                @DisplayName("Should prefer more specific fallback over less specific")
                void shouldPreferMoreSpecificFallback() {
                        // Arrange
                        UUID specificAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                        UUID broadAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                        Map<String, String> requestDimensions = Map.of(
                                        "businessUnitId", "BU-001",
                                        "locationId", "LOC-100",
                                        "departmentId", "DEPT-50",
                                        "costCenterId", "CC-200");

                        // Two fallback mappings at different levels of specificity
                        Map<String, String> specificDimensions = Map.of(
                                        "businessUnitId", "BU-001",
                                        "locationId", "LOC-100",
                                        "departmentId", "DEPT-50"); // 3 dimensions

                        Map<String, String> broadDimensions = Map.of(
                                        "businessUnitId", "BU-001"); // 1 dimension

                        GLMapping specificMapping = createMapping(specificAccountId, specificDimensions);
                        GLMapping broadMapping = createMapping(broadAccountId, broadDimensions);

                        when(mappingRepository.findAllEffectiveMappings(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(List.of(specificMapping, broadMapping));

                        // Act
                        UUID result = resolver.resolveGLAccount(
                                        postingCategoryId, mappingKeyId, transactionDate, requestDimensions);

                        // Assert — the more specific fallback (3 dims) wins over the broader one (1
                        // dim)
                        assertThat(result).isEqualTo(specificAccountId);
                }

                @Test
                @DisplayName("Should fall through to category default when no fallback matches")
                void shouldFallThroughToCategoryDefault() {
                        // Arrange
                        UUID defaultAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                        UUID unrelatedAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                        Map<String, String> requestDimensions = Map.of(
                                        "businessUnitId", "BU-001",
                                        "locationId", "LOC-100");

                        // Mapping has completely different dimension values
                        Map<String, String> unrelatedDimensions = Map.of(
                                        "businessUnitId", "BU-999",
                                        "locationId", "LOC-999");

                        GLMapping unrelatedMapping = createMapping(unrelatedAccountId, unrelatedDimensions);
                        GLMapping defaultMapping = createMapping(defaultAccountId, null);

                        when(mappingRepository.findAllEffectiveMappings(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(List.of(unrelatedMapping));

                        when(mappingRepository.findEffectiveMapping(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(Optional.of(defaultMapping));

                        // Act
                        UUID result = resolver.resolveGLAccount(
                                        postingCategoryId, mappingKeyId, transactionDate, requestDimensions);

                        // Assert
                        assertThat(result).isEqualTo(defaultAccountId);
                }
        }

        // ========================================
        // Category Default & Error Tests
        // ========================================

        @Nested
        @DisplayName("Category default and error handling")
        class CategoryDefaultAndErrors {

                @Test
                @DisplayName("Should resolve category default when no dimensional match exists")
                void shouldResolveCategoryDefault() {
                        // Arrange
                        UUID defaultAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                        GLMapping defaultMapping = createMapping(defaultAccountId, null);

                        when(mappingRepository.findAllEffectiveMappings(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(Collections.emptyList());

                        when(mappingRepository.findEffectiveMapping(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(Optional.of(defaultMapping));

                        Map<String, String> dimensions = Map.of("businessUnitId", "BU-001");

                        // Act
                        UUID result = resolver.resolveGLAccount(
                                        postingCategoryId, mappingKeyId, transactionDate, dimensions);

                        // Assert
                        assertThat(result).isEqualTo(defaultAccountId);
                }

                @Test
                @DisplayName("Should throw when no mapping found at any level")
                void shouldThrowWhenNoMappingFound() {
                        // Arrange
                        when(mappingRepository.findAllEffectiveMappings(any(), any(), any()))
                                        .thenReturn(Collections.emptyList());
                        when(mappingRepository.findEffectiveMapping(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(Optional.empty());

                        Map<String, String> dimensions = Map.of("businessUnitId", "BU-001");

                        // Act & Assert
                        assertThatThrownBy(() -> resolver.resolveGLAccount(
                                        postingCategoryId, mappingKeyId, transactionDate, dimensions))
                                        .isInstanceOf(IllegalArgumentException.class)
                                        .hasMessageContaining("No GL mapping found");
                }
        }

        // ========================================
        // Resolution Priority Tests
        // ========================================

        @Nested
        @DisplayName("Resolution priority")
        class ResolutionPriority {

                @Test
                @DisplayName("Should prefer exact match over fallback and default")
                void shouldPreferExactOverFallbackAndDefault() {
                        // Arrange
                        UUID exactAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                        UUID fallbackAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                        UUID defaultAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                        Map<String, String> requestDimensions = Map.of(
                                        "businessUnitId", "BU-001",
                                        "locationId", "LOC-100");

                        GLMapping exactMapping = createMapping(exactAccountId,
                                        Map.of("businessUnitId", "BU-001", "locationId", "LOC-100"));
                        GLMapping fallbackMapping = createMapping(fallbackAccountId,
                                        Map.of("businessUnitId", "BU-001"));
                        GLMapping defaultMapping = createMapping(defaultAccountId, null);

                        when(mappingRepository.findAllEffectiveMappings(
                                        eq(postingCategoryId), eq(mappingKeyId), eq(transactionDate)))
                                        .thenReturn(List.of(exactMapping, fallbackMapping, defaultMapping));

                        // Act
                        UUID result = resolver.resolveGLAccount(
                                        postingCategoryId, mappingKeyId, transactionDate, requestDimensions);

                        // Assert — exact match wins
                        assertThat(result).isEqualTo(exactAccountId);
                }
        }

        // ========================================
        // Helper Methods
        // ========================================

        private GLMapping createMapping(UUID glAccountId, Map<String, String> dimensions) {
                GLMapping mapping = new GLMapping();
                mapping.setGlMappingId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
                mapping.setPostingCategoryId(postingCategoryId);
                mapping.setMappingKeyId(mappingKeyId);
                mapping.setGlAccountId(glAccountId);
                mapping.setEffectiveStartDate(LocalDateTime.of(2025, 1, 1, 0, 0));
                mapping.setEffectiveEndDate(null);
                mapping.setSourceSystem("POS");
                mapping.setExternalCode("TEST-CODE");
                mapping.setCreatedAt(Instant.now(TEST_CLOCK));
                mapping.setCreatedBy("SYSTEM");
                mapping.setDimensions(dimensions != null ? new HashMap<>(dimensions) : null);
                return mapping;
        }
}
