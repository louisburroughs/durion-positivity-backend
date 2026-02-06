package com.positivity.workorder.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.positivity.workorder.internal.dto.CreateEstimateRequest;
import com.positivity.workorder.internal.entity.ApprovalConfiguration;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateStatus;
import com.positivity.workorder.internal.repository.ApprovalConfigurationRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EstimateServiceTest {

        @Mock
        private EstimateRepository estimateRepository;

        @Mock
        private ApprovalConfigurationRepository approvalConfigurationRepository;

        @InjectMocks
        private EstimateService estimateService;

        private CreateEstimateRequest validRequest;
        private ApprovalConfiguration defaultConfig;
        private UUID testUserId;
        private UUID testConfigId;

        @BeforeEach
        void setUp() {
                testUserId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
                testConfigId = UUID.fromString("550e8400-e29b-41d4-a716-446655440010");

                validRequest = CreateEstimateRequest.builder()
                                .customerId(UUID.fromString("550e8400-e29b-41d4-a716-446655440011"))
                                .vehicleId(UUID.fromString("550e8400-e29b-41d4-a716-446655440012"))
                                .build();

                defaultConfig = ApprovalConfiguration.builder()
                                .id(testConfigId)
                                .approvalMethod(ApprovalConfiguration.ApprovalMethod.CLICK_CONFIRM)
                                .declineExpiryDays(30)
                                .requireSignature(false)
                                .priority(0)
                                .build();

                // Setup default mocks
                when(approvalConfigurationRepository.findApplicableConfigurations(any(UUID.class), any(UUID.class)))
                                .thenReturn(List.of(defaultConfig));

                when(estimateRepository.existsByLocationIdAndEstimateNumber(any(UUID.class), anyString()))
                                .thenReturn(false);
        }

        @Test
        void testCreateEstimate_Success() {
                // Given
                UUID testEstimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440020");
                Estimate savedEstimate = Estimate.builder()
                                .id(testEstimateId)
                                .estimateNumber("EST-2024-1000")
                                .customerId(UUID.fromString("550e8400-e29b-41d4-a716-446655440011"))
                                .vehicleId(UUID.fromString("550e8400-e29b-41d4-a716-446655440012"))
                                .locationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440013"))
                                .currencyUomId("USD")
                                .taxRegionId(UUID.fromString("550e8400-e29b-41d4-a716-446655440014"))
                                .status(EstimateStatus.DRAFT)
                                .createdByUserId(testUserId)
                                .build();

                when(estimateRepository.save(any(Estimate.class)))
                                .thenReturn(savedEstimate);

                // When
                Estimate result = estimateService.createEstimate(validRequest, testUserId);

                // Then
                assertNotNull(result);
                assertEquals(EstimateStatus.DRAFT, result.getStatus());
                assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440011"), result.getCustomerId());
                assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440012"), result.getVehicleId());
                assertNotNull(result.getEstimateNumber());
                assertEquals(testUserId, result.getCreatedByUserId());

                // Verify repository was called
                verify(estimateRepository, times(1)).save(any(Estimate.class));

                // Verify the estimate saved has correct structure
                ArgumentCaptor<Estimate> estimateCaptor = ArgumentCaptor.forClass(Estimate.class);
                verify(estimateRepository).save(estimateCaptor.capture());
                Estimate capturedEstimate = estimateCaptor.getValue();

                assertEquals(EstimateStatus.DRAFT, capturedEstimate.getStatus());
                assertNotNull(capturedEstimate.getCreatedAt());
                assertNotNull(capturedEstimate.getEstimateNumber());
        }

        @Test
        void testCreateEstimate_MissingCustomerId_ThrowsException() {
                // Given
                CreateEstimateRequest invalidRequest = CreateEstimateRequest.builder()
                                .vehicleId(UUID.fromString("550e8400-e29b-41d4-a716-446655440012"))
                                .build();

                // When & Then
                IllegalArgumentException exception = assertThrows(
                                IllegalArgumentException.class,
                                () -> estimateService.createEstimate(invalidRequest, testUserId));

                assertEquals("customerId is required", exception.getMessage());
                verify(estimateRepository, never()).save(any(Estimate.class));
        }

        @Test
        void testCreateEstimate_MissingVehicleId_ThrowsException() {
                // Given
                CreateEstimateRequest invalidRequest = CreateEstimateRequest.builder()
                                .customerId(UUID.fromString("550e8400-e29b-41d4-a716-446655440011"))
                                .build();

                // When & Then
                IllegalArgumentException exception = assertThrows(
                                IllegalArgumentException.class,
                                () -> estimateService.createEstimate(invalidRequest, testUserId));

                assertEquals("vehicleId is required", exception.getMessage());
                verify(estimateRepository, never()).save(any(Estimate.class));
        }

        @Test
        void testCreateEstimate_AppliesDefaultValues() {
                // Given
                when(estimateRepository.save(any(Estimate.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                // When
                Estimate result = estimateService.createEstimate(validRequest, testUserId);

                // Then
                assertNotNull(result);
                assertEquals("USD", result.getCurrencyUomId(), "Should use default currency");
                assertNotNull(result.getLocationId(), "Should have default location");
                assertNotNull(result.getTaxRegionId(), "Should have default tax region");
        }

        @Test
        void testCreateEstimate_WithProvidedLocation() {
                // Given
                CreateEstimateRequest requestWithLocation = CreateEstimateRequest.builder()
                                .customerId(UUID.fromString("550e8400-e29b-41d4-a716-446655440011"))
                                .vehicleId(UUID.fromString("550e8400-e29b-41d4-a716-446655440012"))
                                .locationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440015"))
                                .build();

                when(estimateRepository.save(any(Estimate.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                // When
                Estimate result = estimateService.createEstimate(requestWithLocation, testUserId);

                // Then
                assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440015"), result.getLocationId(),
                                "Should use provided location");
        }

        @Test
        void testCreateEstimate_GeneratesUniqueEstimateNumber() {
                // Given
                when(estimateRepository.save(any(Estimate.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                // When
                Estimate result = estimateService.createEstimate(validRequest, testUserId);

                // Then
                assertNotNull(result.getEstimateNumber());
                assertTrue(result.getEstimateNumber().startsWith("EST-"),
                                "Estimate number should start with EST-");
                assertTrue(result.getEstimateNumber().matches("EST-\\d{4}-\\d+"),
                                "Estimate number should match pattern EST-YYYY-NNNN");
        }

        @Test
        void testCreateEstimate_SetsApprovalConfiguration() {
                // Given
                when(estimateRepository.save(any(Estimate.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                // When
                Estimate result = estimateService.createEstimate(validRequest, testUserId);

                // Then
                assertNotNull(result.getApprovalConfigurationId());
                assertEquals(defaultConfig.getId(), result.getApprovalConfigurationId());
                verify(approvalConfigurationRepository, times(1))
                                .findApplicableConfigurations(any(UUID.class), any(UUID.class));
        }
}
