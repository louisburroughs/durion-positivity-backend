package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.ValidationResult;
import com.positivity.inventory.internal.enums.OverrideReasonCode;
import com.positivity.inventory.internal.exception.LocationAtCapacityException;
import com.positivity.inventory.internal.exception.LocationNotValidForSkuException;
import com.positivity.inventory.internal.exception.NoOnHandAtSourceLocationException;
import com.positivity.inventory.internal.service.PutawayValidationServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

/**
 * Verifies putaway validation orchestration and override behavior.
 *
 * Issue: CAP-221
 */
class PutawayValidationServiceImplTest {

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

                ValidationResult result = service.validatePutawayExecution(request);

                assertThat(result.getWarnings()).extracting(warning -> warning.getCode())
                                .contains("COMPATIBILITY_OVERRIDDEN", "CAPACITY_OVERRIDDEN");
        }

        @Test
        void validatePutawayExecution_rethrowsNoOnHandWhenNoLocationOverrideRequested() {
                PutawayValidationServiceImpl service = Mockito.spy(new PutawayValidationServiceImpl());
                PutawayExecutionRequest request = baseRequest();

                Mockito.doThrow(NoOnHandAtSourceLocationException.class)
                                .when(service)
                                .validateSourceOnHand(anyString(), anyString(), anyInt());

                assertThatThrownBy(() -> service.validatePutawayExecution(request))
                                .isInstanceOf(NoOnHandAtSourceLocationException.class);
        }

        @Test
        void validatePutawayExecution_convertsNoOnHandToWarningWhenLocationOverrideRequested() {
                PutawayValidationServiceImpl service = Mockito.spy(new PutawayValidationServiceImpl());
                PutawayExecutionRequest request = baseRequest();
                request.setOverrideLocationCompatibility(true);

                Mockito.doThrow(NoOnHandAtSourceLocationException.class)
                                .when(service)
                                .validateSourceOnHand(anyString(), anyString(), anyInt());

                ValidationResult result = service.validatePutawayExecution(request);

                assertThat(result.getWarnings()).extracting(warning -> warning.getCode())
                                .contains("SOURCE_RECONCILIATION_NEEDED", "COMPATIBILITY_OVERRIDDEN");
        }

        @Test
        void validatePutawayExecution_rethrowsLocationCompatibilityExceptionWhenNoOverride() {
                PutawayValidationServiceImpl service = Mockito.spy(new PutawayValidationServiceImpl());
                PutawayExecutionRequest request = baseRequest();

                Mockito.doThrow(LocationNotValidForSkuException.class)
                                .when(service)
                                .validateLocationCompatibility(anyString(), anyString());

                assertThatThrownBy(() -> service.validatePutawayExecution(request))
                                .isInstanceOf(LocationNotValidForSkuException.class);
        }

        @Test
        void validatePutawayExecution_rethrowsCapacityExceptionWhenNoCapacityOverride() {
                PutawayValidationServiceImpl service = Mockito.spy(new PutawayValidationServiceImpl());
                PutawayExecutionRequest request = baseRequest();

                Mockito.doThrow(LocationAtCapacityException.class)
                                .when(service)
                                .validateLocationCapacity(anyString(), anyInt());

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

                doReturn(sourceFailure).when(service).validateSourceOnHand(anyString(), anyString(), anyInt());
                doReturn(compatFailure).when(service).validateLocationCompatibility(anyString(), anyString());
                doReturn(capacityWarning).when(service).validateLocationCapacity(anyString(), anyInt());

                ValidationResult result = service.validatePutawayExecution(request);

                assertThat(result.isValid()).isFalse();
                assertThat(result.getErrors()).extracting(error -> error.getErrorCode())
                                .contains("INSUFFICIENT_QUANTITY", "INCOMPATIBLE_LOCATION");
                assertThat(result.getWarnings()).extracting(warning -> warning.getCode())
                                .contains("CAPACITY_NEAR_LIMIT");
        }

        private PutawayExecutionRequest baseRequest() {
                PutawayExecutionRequest request = new PutawayExecutionRequest("SKU-1", "SRC-1", "DEST-1", 3);
                request.setOverrideReasonCode(OverrideReasonCode.OTHER);
                request.setOverrideJustification("test");
                request.setApprovedBy("manager-1");
                return request;
        }
}
