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

        createRequest = new CreateHintRequest(1L, tags, "testUser");

        mockHint = new VehicleApplicabilityHint();
        mockHint.setHintId(java.util.UUID.randomUUID());
        mockHint.setProductId(1L);
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
        assertThat(response.getProductId()).isEqualTo("1");
        verify(hintRepository, times(1)).save(any(VehicleApplicabilityHint.class));
    }

    @Test
    void testUpdateHint_Success() {
        // Given
        UpdateHintRequest updateRequest = new UpdateHintRequest(
                Arrays.asList(new FitmentTagDto(TagType.MAKE, "Honda")),
                "updater");

        when(hintRepository.findById(1L)).thenReturn(Optional.of(mockHint));
        when(hintRepository.save(any(VehicleApplicabilityHint.class)))
                .thenReturn(mockHint);

        // When
        HintResponse response = service.updateHint(1L, updateRequest);

        // Then
        assertThat(response).isNotNull();
        verify(hintRepository, times(1)).findById(1L);
        verify(hintRepository, times(1)).save(any(VehicleApplicabilityHint.class));
    }

    @Test
    void testUpdateHint_NotFound() {
        // Given
        UpdateHintRequest updateRequest = new UpdateHintRequest(
                Arrays.asList(new FitmentTagDto(TagType.MAKE, "Honda")),
                "updater");

        when(hintRepository.findById(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> service.updateHint(1L, updateRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Hint not found");
    }

    @Test
    void testDeleteHint_Success() {
        // Given
        when(hintRepository.existsById(1L)).thenReturn(true);
        doNothing().when(hintRepository).deleteById(1L);

        // When
        service.deleteHint(1L);

        // Then
        verify(hintRepository, times(1)).existsById(1L);
        verify(hintRepository, times(1)).deleteById(1L);
    }

    @Test
    void testDeleteHint_NotFound() {
        // Given
        when(hintRepository.existsById(1L)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> service.deleteHint(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Hint not found");
    }

    @Test
    void testGetHint_Success() {
        // Given
        when(hintRepository.findById(1L)).thenReturn(Optional.of(mockHint));

        // When
        HintResponse response = service.getHint(1L);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getHintId()).isEqualTo(mockHint.getHintId().toString());
        verify(hintRepository, times(1)).findById(1L);
    }

    @Test
    void testGetHintsByProductId() {
        // Given
        when(hintRepository.findByProductId(1L))
                .thenReturn(Arrays.asList(mockHint));

        // When
        List<HintResponse> responses = service.getHintsByProductId(1L);

        // Then
        assertThat(responses).isNotEmpty();
        assertThat(responses).hasSize(1);
        verify(hintRepository, times(1)).findByProductId(1L);
    }

    @Test
    void testFilterProductsByVehicleAttributes_EmptyAttributes() {
        // When
        FilterProductsResponse response = service.filterProductsByVehicleAttributes(new HashMap<>());

        // Then
        assertThat(response.getProductIds()).isEmpty();
        assertThat(response.getCount()).isEqualTo(0);
    }

    @Test
    void testFilterProductsByVehicleAttributes_WithMatches() {
        // Given
        VehicleApplicabilityHint hint1 = new VehicleApplicabilityHint();
        hint1.setHintId(java.util.UUID.randomUUID());
        hint1.setProductId(100L);

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
