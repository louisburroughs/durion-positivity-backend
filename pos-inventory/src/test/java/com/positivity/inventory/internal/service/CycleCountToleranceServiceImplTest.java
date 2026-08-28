package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.cyclecount.service.CycleCountToleranceServiceImpl;
import com.positivity.inventory.internal.dto.cyclecount.tolerance.CreateCycleCountToleranceRequest;
import com.positivity.inventory.internal.dto.cyclecount.tolerance.CycleCountToleranceResponse;
import com.positivity.inventory.internal.dto.cyclecount.tolerance.UpdateCycleCountToleranceRequest;
import com.positivity.inventory.internal.entity.CycleCountTolerance;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.CycleCountToleranceRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CycleCountToleranceServiceImplTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final String LOCATION = "TANK-04";

    @Mock
    private CycleCountToleranceRepository repository;

    private CycleCountToleranceServiceImpl service;

    private CycleCountToleranceServiceImpl newService() {
        return new CycleCountToleranceServiceImpl(repository);
    }

    @Test
    void create_savesAndReturnsResponse() {
        service = newService();
        when(repository.findByProductIdAndStorageLocationAndActiveTrue(PRODUCT_ID, LOCATION))
                .thenReturn(Optional.empty());
        when(repository.save(any(CycleCountTolerance.class))).thenAnswer(invocation -> {
            CycleCountTolerance tolerance = invocation.getArgument(0);
            tolerance.setToleranceId(UUID.randomUUID());
            return tolerance;
        });

        CycleCountToleranceResponse response = service.create(CreateCycleCountToleranceRequest.builder()
                .productId(PRODUCT_ID)
                .storageLocation(LOCATION)
                .absoluteTolerance(new BigDecimal("50"))
                .createdBy("tester")
                .build());

        assertThat(response.getToleranceId()).isNotNull();
        assertThat(response.getAbsoluteTolerance()).isEqualByComparingTo("50");
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void create_rejectsDuplicateActiveScope() {
        service = newService();
        CycleCountTolerance existing = CycleCountTolerance.builder()
                .toleranceId(UUID.randomUUID())
                .productId(PRODUCT_ID)
                .storageLocation(LOCATION)
                .active(true)
                .createdBy("someone-else")
                .build();
        when(repository.findByProductIdAndStorageLocationAndActiveTrue(PRODUCT_ID, LOCATION))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(CreateCycleCountToleranceRequest.builder()
                        .productId(PRODUCT_ID)
                        .storageLocation(LOCATION)
                        .absoluteTolerance(new BigDecimal("50"))
                        .createdBy("tester")
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void update_replacesBoundsVerbatim() {
        service = newService();
        UUID toleranceId = UUID.randomUUID();
        CycleCountTolerance existing = CycleCountTolerance.builder()
                .toleranceId(toleranceId)
                .productId(PRODUCT_ID)
                .storageLocation(LOCATION)
                .absoluteTolerance(new BigDecimal("50"))
                .active(true)
                .createdBy("tester")
                .build();
        when(repository.findById(toleranceId)).thenReturn(Optional.of(existing));
        when(repository.save(any(CycleCountTolerance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CycleCountToleranceResponse response = service.update(
                toleranceId,
                UpdateCycleCountToleranceRequest.builder()
                        .absoluteTolerance(new BigDecimal("75"))
                        .percentageTolerance(new BigDecimal("2"))
                        .active(true)
                        .build());

        assertThat(response.getAbsoluteTolerance()).isEqualByComparingTo("75");
        assertThat(response.getPercentageTolerance()).isEqualByComparingTo("2");
    }

    @Test
    void update_reactivationCollidesWithAnotherActiveRowAtSameScope() {
        service = newService();
        UUID toleranceId = UUID.randomUUID();
        UUID otherToleranceId = UUID.randomUUID();
        CycleCountTolerance inactive = CycleCountTolerance.builder()
                .toleranceId(toleranceId)
                .productId(PRODUCT_ID)
                .storageLocation(LOCATION)
                .active(false)
                .createdBy("tester")
                .build();
        CycleCountTolerance other = CycleCountTolerance.builder()
                .toleranceId(otherToleranceId)
                .productId(PRODUCT_ID)
                .storageLocation(LOCATION)
                .active(true)
                .createdBy("someone-else")
                .build();
        when(repository.findById(toleranceId)).thenReturn(Optional.of(inactive));
        when(repository.findByProductIdAndStorageLocationAndActiveTrue(PRODUCT_ID, LOCATION))
                .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.update(
                        toleranceId,
                        UpdateCycleCountToleranceRequest.builder()
                                .absoluteTolerance(new BigDecimal("10"))
                                .active(true)
                                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot reactivate");
    }

    @Test
    void get_unknownIdThrowsResourceNotFound() {
        service = newService();
        UUID toleranceId = UUID.randomUUID();
        when(repository.findById(toleranceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(toleranceId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_unknownIdThrowsResourceNotFound() {
        service = newService();
        UUID toleranceId = UUID.randomUUID();
        when(repository.existsById(toleranceId)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(toleranceId)).isInstanceOf(ResourceNotFoundException.class);
    }
}
