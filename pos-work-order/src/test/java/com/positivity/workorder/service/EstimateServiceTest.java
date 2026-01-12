package com.positivity.workorder.service;

import com.positivity.workorder.dto.CreateEstimateRequest;
import com.positivity.workorder.entity.ApprovalConfiguration;
import com.positivity.workorder.entity.Customer;
import com.positivity.workorder.entity.Estimate;
import com.positivity.workorder.entity.EstimateSequence;
import com.positivity.workorder.entity.Vehicle;
import com.positivity.workorder.repository.ApprovalConfigurationRepository;
import com.positivity.workorder.repository.CustomerRepository;
import com.positivity.workorder.repository.EstimateRepository;
import com.positivity.workorder.repository.EstimateSequenceRepository;
import com.positivity.workorder.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EstimateServiceTest {

    @Mock
    private EstimateRepository estimateRepository;

    @Mock
    private ApprovalConfigurationRepository approvalConfigurationRepository;

    @Mock
    private EstimateSequenceRepository estimateSequenceRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private VehicleRepository vehicleRepository;

    @InjectMocks
    private EstimateService estimateService;

    private CreateEstimateRequest testRequest;
    private Long testUserId;
    private Customer testCustomer;
    private Vehicle testVehicle;
    private EstimateSequence testSequence;
    private ApprovalConfiguration testConfig;

    @BeforeEach
    void setUp() {
        testUserId = 789L;
        
        testRequest = CreateEstimateRequest.builder()
                .customerId(123L)
                .vehicleId(456L)
                .shopId(1L)
                .build();

        testCustomer = Customer.builder()
                .id(123L)
                .name("John Doe")
                .email("john@example.com")
                .build();

        testVehicle = Vehicle.builder()
                .id(456L)
                .vin("1HGBH41JXMN109186")
                .make("Honda")
                .model("Accord")
                .year("2021")
                .build();

        testSequence = EstimateSequence.builder()
                .id(1L)
                .lastSequenceNumber(10020L)
                .build();

        testConfig = ApprovalConfiguration.builder()
                .id(1L)
                .approvalMethod(ApprovalConfiguration.ApprovalMethod.CLICK_CONFIRM)
                .declineExpiryDays(30)
                .requireSignature(false)
                .priority(0)
                .build();
    }

    @Test
    void createDraftEstimate_Success() {
        // Arrange
        when(customerRepository.existsById(testRequest.getCustomerId())).thenReturn(true);
        when(vehicleRepository.existsById(testRequest.getVehicleId())).thenReturn(true);
        when(estimateSequenceRepository.findAll()).thenReturn(List.of(testSequence));
        when(estimateSequenceRepository.save(any(EstimateSequence.class))).thenReturn(testSequence);
        when(approvalConfigurationRepository.findApplicableConfigurations(any(), any()))
                .thenReturn(List.of(testConfig));

        Estimate savedEstimate = Estimate.builder()
                .id(1L)
                .estimateNumber("EST-10021")
                .customerId(testRequest.getCustomerId())
                .vehicleId(testRequest.getVehicleId())
                .shopId(testRequest.getShopId())
                .status(Estimate.EstimateStatus.DRAFT)
                .createdById(testUserId)
                .approvalConfigurationId(testConfig.getId())
                .build();

        when(estimateRepository.save(any(Estimate.class))).thenReturn(savedEstimate);

        // Act
        Estimate result = estimateService.createDraftEstimate(testRequest, testUserId);

        // Assert
        assertNotNull(result);
        assertEquals("EST-10021", result.getEstimateNumber());
        assertEquals(testRequest.getCustomerId(), result.getCustomerId());
        assertEquals(testRequest.getVehicleId(), result.getVehicleId());
        assertEquals(testRequest.getShopId(), result.getShopId());
        assertEquals(Estimate.EstimateStatus.DRAFT, result.getStatus());
        assertEquals(testUserId, result.getCreatedById());

        // Verify interactions
        verify(customerRepository).existsById(testRequest.getCustomerId());
        verify(vehicleRepository).existsById(testRequest.getVehicleId());
        verify(estimateRepository).save(any(Estimate.class));
        
        // Verify the estimate number was generated correctly
        ArgumentCaptor<Estimate> estimateCaptor = ArgumentCaptor.forClass(Estimate.class);
        verify(estimateRepository).save(estimateCaptor.capture());
        assertEquals("EST-10021", estimateCaptor.getValue().getEstimateNumber());
    }

    @Test
    void createDraftEstimate_CustomerNotFound() {
        // Arrange
        when(customerRepository.existsById(testRequest.getCustomerId())).thenReturn(false);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> estimateService.createDraftEstimate(testRequest, testUserId)
        );

        assertTrue(exception.getMessage().contains("Customer with ID"));
        assertTrue(exception.getMessage().contains("not found"));

        // Verify that we didn't proceed further
        verify(customerRepository).existsById(testRequest.getCustomerId());
        verify(vehicleRepository, never()).existsById(any());
        verify(estimateRepository, never()).save(any());
    }

    @Test
    void createDraftEstimate_VehicleNotFound() {
        // Arrange
        when(customerRepository.existsById(testRequest.getCustomerId())).thenReturn(true);
        when(vehicleRepository.existsById(testRequest.getVehicleId())).thenReturn(false);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> estimateService.createDraftEstimate(testRequest, testUserId)
        );

        assertTrue(exception.getMessage().contains("Vehicle with ID"));
        assertTrue(exception.getMessage().contains("not found"));

        // Verify that we checked customer first, then vehicle
        verify(customerRepository).existsById(testRequest.getCustomerId());
        verify(vehicleRepository).existsById(testRequest.getVehicleId());
        verify(estimateRepository, never()).save(any());
    }

    @Test
    void createDraftEstimate_GeneratesUniqueEstimateNumber() {
        // Arrange
        when(customerRepository.existsById(testRequest.getCustomerId())).thenReturn(true);
        when(vehicleRepository.existsById(testRequest.getVehicleId())).thenReturn(true);
        when(estimateSequenceRepository.findAll()).thenReturn(List.of(testSequence));
        when(estimateSequenceRepository.save(any(EstimateSequence.class))).thenReturn(testSequence);
        when(approvalConfigurationRepository.findApplicableConfigurations(any(), any()))
                .thenReturn(List.of(testConfig));

        when(estimateRepository.save(any(Estimate.class))).thenAnswer(invocation -> {
            Estimate estimate = invocation.getArgument(0);
            estimate.setId(1L);
            return estimate;
        });

        // Act
        Estimate result = estimateService.createDraftEstimate(testRequest, testUserId);

        // Assert
        assertNotNull(result.getEstimateNumber());
        assertTrue(result.getEstimateNumber().startsWith("EST-"));
        assertEquals("EST-10021", result.getEstimateNumber());

        // Verify sequence was incremented
        ArgumentCaptor<EstimateSequence> sequenceCaptor = ArgumentCaptor.forClass(EstimateSequence.class);
        verify(estimateSequenceRepository).save(sequenceCaptor.capture());
        assertEquals(10021L, sequenceCaptor.getValue().getLastSequenceNumber());
    }

    @Test
    void createDraftEstimate_InitializesSequenceIfNotExists() {
        // Arrange
        when(customerRepository.existsById(testRequest.getCustomerId())).thenReturn(true);
        when(vehicleRepository.existsById(testRequest.getVehicleId())).thenReturn(true);
        when(estimateSequenceRepository.findAll()).thenReturn(Collections.emptyList());
        
        EstimateSequence newSequence = EstimateSequence.builder()
                .id(1L)
                .lastSequenceNumber(10000L)
                .build();
        
        when(estimateSequenceRepository.save(any(EstimateSequence.class)))
                .thenAnswer(invocation -> {
                    EstimateSequence seq = invocation.getArgument(0);
                    if (seq.getId() == null) {
                        seq.setId(1L);
                    }
                    return seq;
                });
        
        when(approvalConfigurationRepository.findApplicableConfigurations(any(), any()))
                .thenReturn(List.of(testConfig));

        when(estimateRepository.save(any(Estimate.class))).thenAnswer(invocation -> {
            Estimate estimate = invocation.getArgument(0);
            estimate.setId(1L);
            return estimate;
        });

        // Act
        Estimate result = estimateService.createDraftEstimate(testRequest, testUserId);

        // Assert
        assertNotNull(result);
        assertEquals("EST-10001", result.getEstimateNumber());

        // Verify sequence was created and saved
        verify(estimateSequenceRepository, atLeast(2)).save(any(EstimateSequence.class));
    }
}
