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

import com.positivity.workorder.internal.client.PeopleLocationClient;
import com.positivity.workorder.internal.client.TaxClient;
import com.positivity.workorder.internal.dto.CreateEstimateRequest;
import com.positivity.workorder.internal.dto.EstimateResponse;
import com.positivity.workorder.internal.entity.ApprovalConfiguration;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.repository.ApprovalConfigurationRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.service.EstimateServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EstimateServiceTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private EstimateRepository estimateRepository;

    @Mock
    private ApprovalConfigurationRepository approvalConfigurationRepository;

    @Mock
    private TaxClient taxClient;

    @Mock
    private PeopleLocationClient peopleLocationClient;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EstimateServiceImpl estimateService;

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

        // Requests without an explicit location resolve the creator's primary location.
        when(peopleLocationClient.resolveCurrentUserPrimaryLocation())
                .thenReturn(java.util.Optional.of(UUID.fromString("01960003-0000-7000-8000-000000000001")));
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
                .createdByUserId(testUserId.toString())
                .createdAt(Instant.now(TEST_CLOCK))
                .build();

        when(estimateRepository.save(any(Estimate.class))).thenReturn(savedEstimate);

        // When
        EstimateResponse result = estimateService.createEstimate(validRequest, testUserId.toString());

        // Then
        assertNotNull(result);
        assertEquals(EstimateStatus.DRAFT.name(), result.getStatus());
        assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440011"), result.getCustomerId());
        assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440012"), result.getVehicleId());
        assertNotNull(result.getEstimateNumber());
        assertEquals(testUserId.toString(), result.getCreatedByUserId());
        assertNotNull(result.getCreatedAt());

        // Verify repository was called
        verify(estimateRepository, times(1)).save(any(Estimate.class));

        // Verify the estimate saved has correct structure
        ArgumentCaptor<Estimate> estimateCaptor = ArgumentCaptor.forClass(Estimate.class);
        verify(estimateRepository).save(estimateCaptor.capture());
        Estimate capturedEstimate = estimateCaptor.getValue();

        assertEquals(EstimateStatus.DRAFT, capturedEstimate.getStatus());
        assertNotNull(capturedEstimate.getEstimateNumber());
    }

    @Test
    void testCreateEstimate_MissingCustomerId_ThrowsException() {
        // Given
        CreateEstimateRequest invalidRequest = CreateEstimateRequest.builder()
                .vehicleId(UUID.fromString("550e8400-e29b-41d4-a716-446655440012"))
                .build();
        String userId = testUserId.toString();

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> estimateService.createEstimate(invalidRequest, userId));

        assertEquals("customerId is required", exception.getMessage());
        verify(estimateRepository, never()).save(any(Estimate.class));
    }

    @Test
    void testCreateEstimate_MissingVehicleId_ThrowsException() {
        // Given
        CreateEstimateRequest invalidRequest = CreateEstimateRequest.builder()
                .customerId(UUID.fromString("550e8400-e29b-41d4-a716-446655440011"))
                .build();
        String userId = testUserId.toString();

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> estimateService.createEstimate(invalidRequest, userId));

        assertEquals("vehicleId is required", exception.getMessage());
        verify(estimateRepository, never()).save(any(Estimate.class));
    }

    @Test
    void testCreateEstimate_AppliesDefaultValues() {
        // Given
        when(estimateRepository.save(any(Estimate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        EstimateResponse result = estimateService.createEstimate(validRequest, testUserId.toString());

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

        when(estimateRepository.save(any(Estimate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        EstimateResponse result = estimateService.createEstimate(requestWithLocation, testUserId.toString());

        // Then
        assertEquals(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440015"),
                result.getLocationId(),
                "Should use provided location");
    }

    @Test
    void testCreateEstimate_GeneratesUniqueEstimateNumber() {
        // Given
        when(estimateRepository.save(any(Estimate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        EstimateResponse result = estimateService.createEstimate(validRequest, testUserId.toString());

        // Then
        assertNotNull(result.getEstimateNumber());
        assertTrue(result.getEstimateNumber().startsWith("EST-"), "Estimate number should start with EST-");
        assertTrue(
                result.getEstimateNumber().matches("EST-\\d{4}-\\d+"),
                "Estimate number should match pattern EST-YYYY-NNNN");
    }

    @Test
    void testCreateEstimate_SetsApprovalConfiguration() {
        // Given
        when(estimateRepository.save(any(Estimate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        estimateService.createEstimate(validRequest, testUserId.toString());

        // Then - verify the repository method was called to fetch approval
        // configuration
        verify(approvalConfigurationRepository, times(1))
                .findApplicableConfigurations(any(UUID.class), any(UUID.class));
    }
}
