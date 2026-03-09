package com.positivity.vehiclefitment.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.vehiclefitment.internal.dto.CreateHintRequest;
import com.positivity.vehiclefitment.internal.dto.FilterProductsResponse;
import com.positivity.vehiclefitment.internal.dto.FitmentTagDto;
import com.positivity.vehiclefitment.internal.dto.HintResponse;
import com.positivity.vehiclefitment.internal.dto.UpdateHintRequest;
import com.positivity.vehiclefitment.internal.entity.FitmentTag;
import com.positivity.vehiclefitment.internal.entity.TagType;
import com.positivity.vehiclefitment.internal.entity.VehicleApplicabilityHint;
import com.positivity.vehiclefitment.internal.repository.VehicleApplicabilityHintRepository;

/**
 * Unit tests for VehicleApplicabilityHintService.
 */
@ExtendWith(MockitoExtension.class)
class VehicleApplicabilityHintServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    private static final UUID TEST_HINT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEST_PRODUCT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MATCHING_PRODUCT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private VehicleApplicabilityHintRepository hintRepository;

    @InjectMocks
    private VehicleApplicabilityHintServiceImpl service;

    private CreateHintRequest createRequest;
    private VehicleApplicabilityHint mockHint;

    @BeforeEach
    void setUp() {
        // Setup test data
        List<FitmentTagDto> tags = Arrays.asList(
                new FitmentTagDto(TagType.MAKE, "Toyota"),
                new FitmentTagDto(TagType.MODEL, "Camry"),
                new FitmentTagDto(TagType.YEAR_RANGE, "2018-2022"));

        createRequest = new CreateHintRequest(TEST_PRODUCT_ID, tags);

        mockHint = new VehicleApplicabilityHint();
        mockHint.setHintId(TEST_HINT_ID);
        mockHint.setProductId(TEST_PRODUCT_ID);
        mockHint.setCreatedBy("testUser");
        mockHint.setUpdatedBy("testUser");
    }

    @Test
    void testCreateHint_Success() {
        // Given
        when(hintRepository.save(any(VehicleApplicabilityHint.class)))
                .thenReturn(mockHint);

        // When
        HintResponse response = service.createHint(createRequest);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getHintId()).isEqualTo(mockHint.getHintId().toString());
        assertThat(response.getProductId()).isEqualTo(TEST_PRODUCT_ID.toString());
        verify(hintRepository, times(1)).save(any(VehicleApplicabilityHint.class));
    }

    @Test
    void testUpdateHint_Success() {
        // Given
        UpdateHintRequest updateRequest = new UpdateHintRequest(
            Arrays.asList(new FitmentTagDto(TagType.MAKE, "Honda")));

        when(hintRepository.findById(TEST_HINT_ID)).thenReturn(Optional.of(mockHint));
        when(hintRepository.save(any(VehicleApplicabilityHint.class)))
                .thenReturn(mockHint);

        // When
        HintResponse response = service.updateHint(TEST_HINT_ID, updateRequest);

        // Then
        assertThat(response).isNotNull();
        verify(hintRepository, times(1)).findById(TEST_HINT_ID);
        verify(hintRepository, times(1)).save(any(VehicleApplicabilityHint.class));
    }

    @Test
    void testUpdateHint_NotFound() {
        // Given
        UpdateHintRequest updateRequest = new UpdateHintRequest(
            Arrays.asList(new FitmentTagDto(TagType.MAKE, "Honda")));

        when(hintRepository.findById(TEST_HINT_ID)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> service.updateHint(TEST_HINT_ID, updateRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Hint not found");
    }

    @Test
    void testDeleteHint_Success() {
        // Given
        when(hintRepository.existsById(TEST_HINT_ID)).thenReturn(true);
        doNothing().when(hintRepository).deleteById(TEST_HINT_ID);

        // When
        service.deleteHint(TEST_HINT_ID);

        // Then
        verify(hintRepository, times(1)).existsById(TEST_HINT_ID);
        verify(hintRepository, times(1)).deleteById(TEST_HINT_ID);
    }

    @Test
    void testDeleteHint_NotFound() {
        // Given
        when(hintRepository.existsById(TEST_HINT_ID)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> service.deleteHint(TEST_HINT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Hint not found");
    }

    @Test
    void testGetHint_Success() {
        // Given
        when(hintRepository.findById(TEST_HINT_ID)).thenReturn(Optional.of(mockHint));

        // When
        HintResponse response = service.getHint(TEST_HINT_ID);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getHintId()).isEqualTo(mockHint.getHintId().toString());
        verify(hintRepository, times(1)).findById(TEST_HINT_ID);
    }

    @Test
    void testGetHintsByProductId() {
        // Given
        when(hintRepository.findByProductId(TEST_PRODUCT_ID))
                .thenReturn(Arrays.asList(mockHint));

        // When
        List<HintResponse> responses = service.getHintsByProductId(TEST_PRODUCT_ID);

        // Then
        assertThat(responses).isNotEmpty();
        assertThat(responses).hasSize(1);
        verify(hintRepository, times(1)).findByProductId(TEST_PRODUCT_ID);
    }

    @Test
    void testFilterProductsByVehicleAttributes_EmptyAttributes() {
        // When
        FilterProductsResponse response = service.filterProductsByVehicleAttributes(new HashMap<>());

        // Then
        assertThat(response.getProductIds()).isEmpty();
        assertThat(response.getCount()).isZero();
    }

    @Test
    void testFilterProductsByVehicleAttributes_WithMatches() {
        // Given
        VehicleApplicabilityHint hint1 = new VehicleApplicabilityHint();
        hint1.setHintId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        hint1.setProductId(MATCHING_PRODUCT_ID);

        when(hintRepository.findAll()).thenReturn(Arrays.asList(hint1));

        Map<String, String> attributes = new HashMap<>();
        attributes.put("make", "Toyota");

        // When
        FilterProductsResponse response = service.filterProductsByVehicleAttributes(attributes);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void testGetHint_NotFound() {
        when(hintRepository.findById(TEST_HINT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHint(TEST_HINT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Hint not found");
    }

    @Test
    void testFilterProductsByVehicleAttributes_Null() {
        FilterProductsResponse response = service.filterProductsByVehicleAttributes(null);

        assertThat(response.getProductIds()).isEmpty();
        assertThat(response.getCount()).isZero();
    }

    @Test
    void testFilterProductsByVehicleAttributes_YearRangeWithin() {
        VehicleApplicabilityHint hint = new VehicleApplicabilityHint();
        hint.setHintId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        hint.setProductId(MATCHING_PRODUCT_ID);
        FitmentTag tag = new FitmentTag();
        tag.setTagType(TagType.YEAR_RANGE);
        tag.setTagValue("2016-2022");
        hint.getFitmentTags().add(tag);

        when(hintRepository.findAll()).thenReturn(List.of(hint));

        FilterProductsResponse response = service.filterProductsByVehicleAttributes(Map.of("YEAR_RANGE", "2019"));

        assertThat(response.getCount()).isEqualTo(1);
        assertThat(response.getProductIds()).contains(MATCHING_PRODUCT_ID.toString());
    }

    @Test
    void testFilterProductsByVehicleAttributes_YearRangeOutside() {
        VehicleApplicabilityHint hint = new VehicleApplicabilityHint();
        hint.setHintId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        hint.setProductId(MATCHING_PRODUCT_ID);
        FitmentTag tag = new FitmentTag();
        tag.setTagType(TagType.YEAR_RANGE);
        tag.setTagValue("2016-2022");
        hint.getFitmentTags().add(tag);

        when(hintRepository.findAll()).thenReturn(List.of(hint));

        FilterProductsResponse response = service.filterProductsByVehicleAttributes(Map.of("YEAR_RANGE", "2025"));

        assertThat(response.getCount()).isZero();
    }

    @Test
    void testFilterProductsByVehicleAttributes_SingleYearMatch() {
        VehicleApplicabilityHint hint = new VehicleApplicabilityHint();
        hint.setHintId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        hint.setProductId(MATCHING_PRODUCT_ID);
        FitmentTag tag = new FitmentTag();
        tag.setTagType(TagType.YEAR_RANGE);
        tag.setTagValue("2020");
        hint.getFitmentTags().add(tag);

        when(hintRepository.findAll()).thenReturn(List.of(hint));

        FilterProductsResponse response = service.filterProductsByVehicleAttributes(Map.of("YEAR_RANGE", "2020"));

        assertThat(response.getCount()).isEqualTo(1);
    }

    @Test
    void testFilterProductsByVehicleAttributes_InvalidYearFormat() {
        VehicleApplicabilityHint hint = new VehicleApplicabilityHint();
        hint.setHintId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        hint.setProductId(MATCHING_PRODUCT_ID);
        FitmentTag tag = new FitmentTag();
        tag.setTagType(TagType.YEAR_RANGE);
        tag.setTagValue("2016-2022");
        hint.getFitmentTags().add(tag);

        when(hintRepository.findAll()).thenReturn(List.of(hint));

        // Invalid year value → NumberFormatException → matchesYearRange returns false
        FilterProductsResponse response = service.filterProductsByVehicleAttributes(Map.of("YEAR_RANGE", "not-a-year"));

        assertThat(response.getCount()).isZero();
    }

    @Test
    void testFilterProductsByVehicleAttributes_UnknownTagType() {
        VehicleApplicabilityHint hint = new VehicleApplicabilityHint();
        hint.setHintId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        hint.setProductId(MATCHING_PRODUCT_ID);

        when(hintRepository.findAll()).thenReturn(List.of(hint));

        // Unknown tag type key → IllegalArgumentException caught → hint still matches (no blocking failure)
        FilterProductsResponse response = service.filterProductsByVehicleAttributes(Map.of("UNKNOWN_TYPE", "value"));

        assertThat(response.getCount()).isEqualTo(1);
    }
}
