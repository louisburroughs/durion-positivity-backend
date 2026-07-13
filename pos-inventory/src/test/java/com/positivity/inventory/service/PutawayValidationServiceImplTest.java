package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.service.StorageLocationValidationService;
import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.ValidationResult;
import com.positivity.inventory.internal.enums.OverrideReasonCode;
import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.inventory.internal.exception.LocationAtCapacityException;
import com.positivity.inventory.internal.exception.LocationNotValidForSkuException;
import com.positivity.inventory.internal.exception.NoOnHandAtSourceLocationException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.security.PutawayPermissions;
import com.positivity.inventory.internal.service.PutawayValidationServiceImpl;
import com.positivity.security.common.GatewaySecurityConstants;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Verifies putaway validation orchestration and override behavior.
 */
class PutawayValidationServiceImplTest {
    private static final UUID SRC_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEST_1 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validatePutawayExecution_returnsValidWhenNoOverridesAndChecksPass() {
        PutawayValidationServiceImpl service = new PutawayValidationServiceImpl();

        ValidationResult result = service.validatePutawayExecution(baseRequest());

        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    void validatePutawayExecution_addsOverrideWarningsWhenOverridesAreEnabled() {
        PutawayValidationServiceImpl service = new PutawayValidationServiceImpl();
        PutawayExecutionRequest request = baseRequest();
        request.setOverrideLocationCompatibility(true);
        request.setOverrideCapacity(true);
        authenticateAs(
                "putaway-manager",
                PutawayPermissions.OVERRIDE_LOCATION_COMPATIBILITY,
                PutawayPermissions.OVERRIDE_LOCATION_CAPACITY);

        ValidationResult result = service.validatePutawayExecution(request);

        assertThat(result.getWarnings())
                .extracting(warning -> warning.getCode())
                .contains("COMPATIBILITY_OVERRIDDEN", "CAPACITY_OVERRIDDEN");
    }

    @Test
    void validatePutawayExecution_throwsWhenCompatibilityOverridePermissionMissing() {
        PutawayValidationServiceImpl service = new PutawayValidationServiceImpl();
        PutawayExecutionRequest request = baseRequest();
        request.setOverrideLocationCompatibility(true);
        authenticateAs("putaway-user");

        assertThatThrownBy(() -> service.validatePutawayExecution(request))
                .isInstanceOf(InsufficientPermissionException.class);
    }

    @Test
    void validatePutawayExecution_rethrowsNoOnHandWhenNoLocationOverrideRequested() {
        PutawayValidationServiceImpl service = Mockito.spy(new PutawayValidationServiceImpl());
        PutawayExecutionRequest request = baseRequest();

        Mockito.doThrow(new NoOnHandAtSourceLocationException(SRC_1, "SKU-1"))
                .when(service)
                .validateSourceOnHand(any(), anyString(), anyInt());

        assertThatThrownBy(() -> service.validatePutawayExecution(request))
                .isInstanceOf(NoOnHandAtSourceLocationException.class);
    }

    @Test
    void validatePutawayExecution_convertsNoOnHandToWarningWhenLocationOverrideRequested() {
        PutawayValidationServiceImpl service = Mockito.spy(new PutawayValidationServiceImpl());
        PutawayExecutionRequest request = baseRequest();
        request.setOverrideLocationCompatibility(true);
        authenticateAs("putaway-manager", PutawayPermissions.OVERRIDE_LOCATION_COMPATIBILITY);

        Mockito.doThrow(new NoOnHandAtSourceLocationException(SRC_1, "SKU-1"))
                .when(service)
                .validateSourceOnHand(any(), anyString(), anyInt());

        ValidationResult result = service.validatePutawayExecution(request);

        assertThat(result.getWarnings())
                .extracting(warning -> warning.getCode())
                .contains("SOURCE_RECONCILIATION_NEEDED", "COMPATIBILITY_OVERRIDDEN");
    }

    @Test
    void validatePutawayExecution_rethrowsLocationCompatibilityExceptionWhenNoOverride() {
        PutawayValidationServiceImpl service = Mockito.spy(new PutawayValidationServiceImpl());
        PutawayExecutionRequest request = baseRequest();

        Mockito.doThrow(new LocationNotValidForSkuException(DEST_1, "SKU-1", "incompatible"))
                .when(service)
                .validateLocationCompatibility(any(), anyString());

        assertThatThrownBy(() -> service.validatePutawayExecution(request))
                .isInstanceOf(LocationNotValidForSkuException.class);
    }

    @Test
    void validatePutawayExecution_rethrowsCapacityExceptionWhenNoCapacityOverride() {
        PutawayValidationServiceImpl service = Mockito.spy(new PutawayValidationServiceImpl());
        PutawayExecutionRequest request = baseRequest();

        Mockito.doThrow(new LocationAtCapacityException(DEST_1, 100, 100))
                .when(service)
                .validateLocationCapacity(any(), anyInt());

        assertThatThrownBy(() -> service.validatePutawayExecution(request))
                .isInstanceOf(LocationAtCapacityException.class);
    }

    @Test
    void validatePutawayExecution_mergesErrorsAndWarningsFromChildValidations() {
        PutawayValidationServiceImpl service = Mockito.spy(new PutawayValidationServiceImpl());
        PutawayExecutionRequest request = baseRequest();

        ValidationResult sourceFailure = ValidationResult.failure("INSUFFICIENT_QUANTITY", "not enough stock");
        ValidationResult compatFailure = ValidationResult.failure("INCOMPATIBLE_LOCATION", "cannot store item");
        ValidationResult capacityWarning = ValidationResult.success();
        capacityWarning.addWarning("CAPACITY_NEAR_LIMIT", "near capacity");

        doReturn(sourceFailure).when(service).validateSourceOnHand(any(), anyString(), anyInt());
        doReturn(compatFailure).when(service).validateLocationCompatibility(any(), anyString());
        doReturn(capacityWarning).when(service).validateLocationCapacity(any(), anyInt());

        ValidationResult result = service.validatePutawayExecution(request);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors())
                .extracting(error -> error.getErrorCode())
                .contains("INSUFFICIENT_QUANTITY", "INCOMPATIBLE_LOCATION");
        assertThat(result.getWarnings())
                .extracting(warning -> warning.getCode())
                .contains("CAPACITY_NEAR_LIMIT");
    }

    @Test
    void validateLocationCapacity_throwsWhenDestinationStorageLocationInactive() {
        InventoryLedgerEntryRepository ledger = mock(InventoryLedgerEntryRepository.class);
        PutawayRuleRepository putawayRuleRepository = mock(PutawayRuleRepository.class);
        ReplenishmentPolicyRepository replenishmentPolicyRepository = mock(ReplenishmentPolicyRepository.class);
        StorageLocationValidationService locationValidationClient = mock(StorageLocationValidationService.class);

        StorageLocationValidationService.StorageLocationValidation validation =
                new StorageLocationValidationService.StorageLocationValidation();
        validation.setExists(true);
        validation.setActive(false);

        when(locationValidationClient.getStorageLocationValidation(DEST_1.toString()))
                .thenReturn(validation);

        PutawayValidationServiceImpl service = new PutawayValidationServiceImpl(
                ledger, putawayRuleRepository, replenishmentPolicyRepository, locationValidationClient);

        assertThatThrownBy(() -> service.validateLocationCapacity(DEST_1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inactive");
    }

    @Test
    void validateLocationCapacity_throwsWhenProjectedCapacityMeetsConfiguredMaximum() {
        InventoryLedgerEntryRepository ledger = mock(InventoryLedgerEntryRepository.class);
        PutawayRuleRepository putawayRuleRepository = mock(PutawayRuleRepository.class);
        ReplenishmentPolicyRepository replenishmentPolicyRepository = mock(ReplenishmentPolicyRepository.class);
        StorageLocationValidationService locationValidationClient = mock(StorageLocationValidationService.class);

        StorageLocationValidationService.StorageLocationValidation validation =
                new StorageLocationValidationService.StorageLocationValidation();
        validation.setExists(true);
        validation.setActive(true);

        when(locationValidationClient.getStorageLocationValidation(DEST_1.toString()))
                .thenReturn(validation);
        when(ledger.calculateOnHandQuantityAtLocation(eq(DEST_1), anyList())).thenReturn(7);
        when(replenishmentPolicyRepository.sumMaximumQuantityByLocationId(DEST_1))
                .thenReturn(10);

        PutawayValidationServiceImpl service = new PutawayValidationServiceImpl(
                ledger, putawayRuleRepository, replenishmentPolicyRepository, locationValidationClient);

        assertThatThrownBy(() -> service.validateLocationCapacity(DEST_1, 3))
                .isInstanceOf(LocationAtCapacityException.class);
    }

    @Test
    void validateLocationCapacity_addsWarningWhenOverfillIsWithinTolerance() {
        InventoryLedgerEntryRepository ledger = mock(InventoryLedgerEntryRepository.class);
        PutawayRuleRepository putawayRuleRepository = mock(PutawayRuleRepository.class);
        ReplenishmentPolicyRepository replenishmentPolicyRepository = mock(ReplenishmentPolicyRepository.class);
        StorageLocationValidationService locationValidationClient = mock(StorageLocationValidationService.class);

        StorageLocationValidationService.StorageLocationValidation validation =
                new StorageLocationValidationService.StorageLocationValidation();
        validation.setExists(true);
        validation.setActive(true);

        when(locationValidationClient.getStorageLocationValidation(DEST_1.toString()))
                .thenReturn(validation);
        when(ledger.calculateOnHandQuantityAtLocation(eq(DEST_1), anyList())).thenReturn(10);
        when(replenishmentPolicyRepository.sumMaximumQuantityByLocationId(DEST_1))
                .thenReturn(12);

        PutawayValidationServiceImpl service = new PutawayValidationServiceImpl(
                ledger, putawayRuleRepository, replenishmentPolicyRepository, locationValidationClient);

        ValidationResult result = service.validateLocationCapacity(DEST_1, 3);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings())
                .extracting(warning -> warning.getCode())
                .contains("CAPACITY_NEAR_LIMIT");
    }

    @Test
    void validateLocationCapacity_prefersLocationUnitCapacityOverPolicyCapacity() {
        InventoryLedgerEntryRepository ledger = mock(InventoryLedgerEntryRepository.class);
        PutawayRuleRepository putawayRuleRepository = mock(PutawayRuleRepository.class);
        ReplenishmentPolicyRepository replenishmentPolicyRepository = mock(ReplenishmentPolicyRepository.class);
        StorageLocationValidationService locationValidationClient = mock(StorageLocationValidationService.class);

        StorageLocationValidationService.StorageLocationValidation validation =
                new StorageLocationValidationService.StorageLocationValidation();
        validation.setExists(true);
        validation.setActive(true);
        validation.setMaxUnitCapacity(10);

        when(locationValidationClient.getStorageLocationValidation(DEST_1.toString()))
                .thenReturn(validation);
        when(ledger.calculateOnHandQuantityAtLocation(eq(DEST_1), anyList())).thenReturn(8);
        when(replenishmentPolicyRepository.sumMaximumQuantityByLocationId(DEST_1))
                .thenReturn(1000);

        PutawayValidationServiceImpl service = new PutawayValidationServiceImpl(
                ledger, putawayRuleRepository, replenishmentPolicyRepository, locationValidationClient);

        assertThatThrownBy(() -> service.validateLocationCapacity(DEST_1, 2))
                .isInstanceOf(LocationAtCapacityException.class);
    }

    private PutawayExecutionRequest baseRequest() {
        PutawayExecutionRequest request = new PutawayExecutionRequest("SKU-1", SRC_1, DEST_1, 3);
        request.setOverrideReasonCode(OverrideReasonCode.OTHER);
        request.setOverrideJustification("test");
        request.setApprovedBy("manager-1");
        return request;
    }

    private void authenticateAs(String username, String... authorities) {
        var auth = new UsernamePasswordAuthenticationToken(
                username,
                "N/A",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
