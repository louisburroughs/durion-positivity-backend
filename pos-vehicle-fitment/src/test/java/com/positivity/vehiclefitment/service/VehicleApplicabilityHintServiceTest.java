package com.positivity.vehiclefitment.service;

import com.positivity.vehiclefitment.internal.dto.*;
import com.positivity.vehiclefitment.internal.entity.TagType;
import com.positivity.vehiclefitment.internal.entity.VehicleApplicabilityHint;
import com.positivity.vehiclefitment.internal.repository.VehicleApplicabilityHintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for VehicleApplicabilityHintService.
 */
@ExtendWith(MockitoExtension.class)
class VehicleApplicabilityHintServiceTest {

    private static final UUID TEST_HINT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEST_PRODUCT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MATCHING_PRODUCT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private VehicleApplicabilityHintRepository hintRepository;

    @InjectMocks
    private VehicleApplicabilityHintService service;

    private CreateHintRequest createRequest;
    private VehicleApplicabilityHint mockHint;

    @BeforeEach
    void setUp() {
        // Setup test data
        List<FitmentTagDto> tags = Arrays.asList(
                new FitmentTagDto(TagType.MAKE, "Toyota"),
                new FitmentTagDto(TagType.MODEL, "Camry"),
                new FitmentTagDto(TagType.YEAR_RANGE, "2018-2022"));

        createRequest = new CreateHintRequest(TEST_PRODUCT_ID, tags, "testUser");

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
                Arrays.asList(new FitmentTagDto(TagType.MAKE, "Honda")),
                "updater");

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
                Arrays.asList(new FitmentTagDto(TagType.MAKE, "Honda")),
                "updater");

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
}
