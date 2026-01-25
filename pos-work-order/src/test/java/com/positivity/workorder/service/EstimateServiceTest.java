package com.positivity.workorder.service;

import com.positivity.workorder.dto.CreateEstimateRequest;
import com.positivity.workorder.entity.ApprovalConfiguration;
import com.positivity.workorder.entity.Estimate;
import com.positivity.workorder.entity.EstimateStatus;
import com.positivity.workorder.repository.ApprovalConfigurationRepository;
import com.positivity.workorder.repository.EstimateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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
        private Long testUserId;

        @BeforeEach
        void setUp() {
                testUserId = 100L;

                validRequest = CreateEstimateRequest.builder()
                                .customerId(1L)
                                .vehicleId(2L)
                                .build();

                defaultConfig = ApprovalConfiguration.builder()
                                .id(1L)
                                .approvalMethod(ApprovalConfiguration.ApprovalMethod.CLICK_CONFIRM)
                                .declineExpiryDays(30)
                                .requireSignature(false)
                                .priority(0)
                                .build();

                // Setup default mocks
                when(approvalConfigurationRepository.findApplicableConfigurations(anyLong(), anyLong()))
                                .thenReturn(List.of(defaultConfig));

                when(estimateRepository.existsByLocationIdAndEstimateNumber(anyLong(), anyString()))
                                .thenReturn(false);
        }

        @Test
        void testCreateEstimate_Success() {
                // Given
                Estimate savedEstimate = Estimate.builder()
                                .id(1L)
                                .estimateNumber("EST-2024-1000")
                                .customerId(1L)
                                .vehicleId(2L)
                                .locationId(1L)
                                .currencyUomId("USD")
                                .taxRegionId(1L)
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
                assertEquals(1L, result.getCustomerId());
                assertEquals(2L, result.getVehicleId());
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
                                .vehicleId(2L)
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
                                .customerId(1L)
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
                                .customerId(1L)
                                .vehicleId(2L)
                                .locationId(5L)
                                .build();

                when(estimateRepository.save(any(Estimate.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                // When
                Estimate result = estimateService.createEstimate(requestWithLocation, testUserId);

                // Then
                assertEquals(5L, result.getLocationId(), "Should use provided location");
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
                                .findApplicableConfigurations(anyLong(), anyLong());
        }
}
