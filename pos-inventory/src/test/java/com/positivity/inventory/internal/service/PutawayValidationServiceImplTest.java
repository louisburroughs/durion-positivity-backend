package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.ValidationResult;
import com.positivity.inventory.internal.enums.OverrideReasonCode;
import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.inventory.internal.exception.LocationAtCapacityException;
import com.positivity.inventory.internal.exception.LocationNotValidForSkuException;
import com.positivity.inventory.internal.exception.NoOnHandAtSourceLocationException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import com.positivity.inventory.internal.security.PutawayPermissions;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Putaway validation: a destination must be enabled for the SKU and have room for it, and both
 * checks may only be overridden with the matching permission plus reason, justification and — for
 * capacity — an approver, while an overfill beyond the 10% tolerance is refused outright.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PutawayValidationServiceImpl")
class PutawayValidationServiceImplTest {

    private static final UUID SOURCE_LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9a01");
    private static final UUID DESTINATION_LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9a02");
    private static final String SKU = "SKU-1";

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Mock
    private PutawayRuleRepository putawayRuleRepository;

    @Mock
    private StorageLocationValidationService storageLocationValidationService;

    @Mock
    private StorageCompatibilityEvaluator storageCompatibilityEvaluator;

    private PutawayValidationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PutawayValidationServiceImpl(
                inventoryLedgerEntryRepository,
                putawayRuleRepository,
                storageLocationValidationService,
                storageCompatibilityEvaluator);
        when(putawayRuleRepository.existsByDestinationLocationIdAndIsEnabledTrue(DESTINATION_LOCATION_ID))
                .thenReturn(true);
        // #1514: physical fitness replaced the two replenishment-policy gates. The evaluator's own
        // matrix logic is covered by StorageCompatibilityEvaluatorTest; here it is a collaborator.
        when(storageCompatibilityEvaluator.evaluate(any(), anyString()))
                .thenReturn(StorageCompatibilityEvaluator.Verdict.accept());
        when(storageLocationValidationService.getStorageLocationValidation(anyString()))
                .thenReturn(locationValidation(true, true, 100));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                        eq(DESTINATION_LOCATION_ID), any(List.class)))
                .thenReturn(new BigDecimal("0"));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                        eq(SKU), eq(SOURCE_LOCATION_ID), any(List.class)))
                .thenReturn(new BigDecimal("50"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static StorageLocationValidationService.StorageLocationValidation locationValidation(
            boolean exists, boolean active, Integer maxUnitCapacity) {
        return locationValidation(exists, active, maxUnitCapacity, "GENERAL", false);
    }

    private static StorageLocationValidationService.StorageLocationValidation locationValidation(
            boolean exists,
            boolean active,
            Integer maxUnitCapacity,
            String storageCategoryCode,
            Boolean hazardContainment) {
        StorageLocationValidationService.StorageLocationValidation validation =
                new StorageLocationValidationService.StorageLocationValidation();
        validation.setStorageLocationId(DESTINATION_LOCATION_ID);
        validation.setExists(exists);
        validation.setActive(active);
        validation.setMaxUnitCapacity(maxUnitCapacity);
        validation.setStorageCategoryCode(storageCategoryCode);
        validation.setHazardContainment(hazardContainment);
        return validation;
    }

    private static void authenticateWith(String... authorities) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                "jane.smith",
                "n/a",
                java.util.Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "jane.smith"));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static PutawayExecutionRequest request(int quantity) {
        PutawayExecutionRequest request = new PutawayExecutionRequest();
        request.setSkuId(SKU);
        request.setSourceLocationId(SOURCE_LOCATION_ID);
        request.setDestinationLocationId(DESTINATION_LOCATION_ID);
        request.setQuantity(quantity);
        return request;
    }

    private static List<String> errorCodes(ValidationResult result) {
        return result.getErrors().stream()
                .map(ValidationResult.ValidationError::getErrorCode)
                .toList();
    }

    private static List<String> warningCodes(ValidationResult result) {
        return result.getWarnings().stream()
                .map(ValidationResult.ValidationWarning::getCode)
                .toList();
    }

    @Nested
    @DisplayName("validateLocationCompatibility")
    class ValidateLocationCompatibility {

        @Test
        void acceptsAnEnabledLocationThatIsPhysicallyFitForTheSku() {
            assertThat(service.validateLocationCompatibility(DESTINATION_LOCATION_ID, SKU)
                            .isValid())
                    .isTrue();
        }

        @Test
        void refusesALocationThatIsNotEnabledForPutaway() {
            when(putawayRuleRepository.existsByDestinationLocationIdAndIsEnabledTrue(DESTINATION_LOCATION_ID))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.validateLocationCompatibility(DESTINATION_LOCATION_ID, SKU))
                    .isInstanceOf(LocationNotValidForSkuException.class)
                    .hasMessageContaining("not enabled for putaway");
        }

        @Test
        @DisplayName("#1514 - a class/capability mismatch is refused, and the reason names it")
        void refusesAClassCapabilityMismatch() {
            when(storageCompatibilityEvaluator.evaluate(any(), anyString()))
                    .thenReturn(StorageCompatibilityEvaluator.Verdict.refuse(
                            "OIL_STORAGE does not accept catalog class Tires & Wheels (accepted: BULK_FLOOR,"
                                    + " TIRE_RACK)"));

            assertThatThrownBy(() -> service.validateLocationCompatibility(DESTINATION_LOCATION_ID, SKU))
                    .isInstanceOf(LocationNotValidForSkuException.class)
                    .hasMessageContaining("OIL_STORAGE does not accept catalog class Tires & Wheels");
        }

        @Test
        @DisplayName("#1514 - the destination's capability is read and handed to the evaluator")
        void passesTheDestinationCapabilityToTheEvaluator() {
            when(storageLocationValidationService.getStorageLocationValidation(anyString()))
                    .thenReturn(locationValidation(true, true, 100, "TIRE_RACK", true));

            service.validateLocationCompatibility(DESTINATION_LOCATION_ID, SKU);

            org.mockito.ArgumentCaptor<StorageLocationValidationService.StorageLocationValidation> captor =
                    org.mockito.ArgumentCaptor.forClass(
                            StorageLocationValidationService.StorageLocationValidation.class);
            verify(storageCompatibilityEvaluator).evaluate(captor.capture(), eq(SKU));
            assertThat(captor.getValue().getStorageCategoryCode()).isEqualTo("TIRE_RACK");
            assertThat(captor.getValue().getHazardContainment()).isTrue();
        }

        @Test
        @DisplayName("#1514 - a SKU with no replenishment policy is no longer refused: that gate is gone")
        void doesNotConsultReplenishmentPoliciesAtAll() {
            // The bug in #1514: a brand-new SKU could never be put away anywhere because eligibility
            // required an (itemSKU, locationId) replenishment-policy row. Nothing here reads one now.
            assertThat(service.validateLocationCompatibility(DESTINATION_LOCATION_ID, "BRAND-NEW-SKU")
                            .isValid())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("validateLocationCapacity")
    class ValidateLocationCapacity {

        @Test
        void acceptsAPutawayWellInsideTheLimitWithNoWarning() {
            ValidationResult result = service.validateLocationCapacity(DESTINATION_LOCATION_ID, 10);

            assertThat(result.isValid()).isTrue();
            assertThat(result.getWarnings()).isEmpty();
        }

        @Test
        void warnsWhenTheProjectedFillIsWithinTenPercentOfTheLimit() {
            ValidationResult result = service.validateLocationCapacity(DESTINATION_LOCATION_ID, 95);

            assertThat(result.isValid()).isTrue();
            assertThat(warningCodes(result)).containsExactly("CAPACITY_NEAR_LIMIT");
        }

        @Test
        void refusesAPutawayThatExactlyFillsTheLocation() {
            assertThatThrownBy(() -> service.validateLocationCapacity(DESTINATION_LOCATION_ID, 100))
                    .isInstanceOf(LocationAtCapacityException.class);
        }

        @Test
        void warnsRatherThanFailsForAnOverfillInsideTheTolerance() {
            ValidationResult result = service.validateLocationCapacity(DESTINATION_LOCATION_ID, 105);

            assertThat(result.isValid()).isTrue();
            assertThat(warningCodes(result)).containsExactly("CAPACITY_NEAR_LIMIT");
        }

        @Test
        void refusesAnOverfillBeyondTheTolerance() {
            assertThatThrownBy(() -> service.validateLocationCapacity(DESTINATION_LOCATION_ID, 120))
                    .isInstanceOf(LocationAtCapacityException.class);
        }

        @Test
        @DisplayName("#1514 - a bin that declares no capacity is uncapped, not full")
        void acceptsALocationWithNoDeclaredCapacity() {
            when(storageLocationValidationService.getStorageLocationValidation(anyString()))
                    .thenReturn(locationValidation(true, true, null));

            ValidationResult result = service.validateLocationCapacity(DESTINATION_LOCATION_ID, 1);

            assertThat(result.isValid()).isTrue();
            assertThat(result.getWarnings()).isEmpty();
        }

        @Test
        @DisplayName("#1514 - a huge putaway into an uncapped bin is still accepted with no warning")
        void anUncappedBinNeverWarns() {
            when(storageLocationValidationService.getStorageLocationValidation(anyString()))
                    .thenReturn(locationValidation(true, true, null));

            ValidationResult result = service.validateLocationCapacity(DESTINATION_LOCATION_ID, 1_000_000);

            assertThat(result.isValid()).isTrue();
            assertThat(result.getWarnings()).isEmpty();
        }

        @Test
        @DisplayName("#1514 - a DECLARED capacity of zero still refuses: it is not the same as undeclared")
        void aDeclaredZeroCapacityStillRefuses() {
            // pos-location can publish maxUnitCapacity = 0 from a capacity descriptor, which is an
            // operator saying "hold nothing here". Only an absent value means uncapped.
            when(storageLocationValidationService.getStorageLocationValidation(anyString()))
                    .thenReturn(locationValidation(true, true, 0));

            assertThatThrownBy(() -> service.validateLocationCapacity(DESTINATION_LOCATION_ID, 10))
                    .isInstanceOf(LocationAtCapacityException.class);
        }

        @Test
        @DisplayName("#1514 - an uncapped bin is not even queried for on-hand: there is nothing to compare")
        void anUncappedBinSkipsTheOnHandRead() {
            when(storageLocationValidationService.getStorageLocationValidation(anyString()))
                    .thenReturn(locationValidation(true, true, null));

            service.validateLocationCapacity(DESTINATION_LOCATION_ID, 10);

            verify(inventoryLedgerEntryRepository, org.mockito.Mockito.never())
                    .calculateOnHandQuantityAtLocation(eq(DESTINATION_LOCATION_ID), any(List.class));
        }

        @Test
        void refusesADestinationThatDoesNotExistOrIsInactive() {
            when(storageLocationValidationService.getStorageLocationValidation(anyString()))
                    .thenReturn(locationValidation(false, false, 100));
            assertThatThrownBy(() -> service.validateLocationCapacity(DESTINATION_LOCATION_ID, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not exist");

            when(storageLocationValidationService.getStorageLocationValidation(anyString()))
                    .thenReturn(locationValidation(true, false, 100));
            assertThatThrownBy(() -> service.validateLocationCapacity(DESTINATION_LOCATION_ID, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("inactive");
        }

        @Test
        void countsExistingStockTowardTheLimit() {
            when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                            eq(DESTINATION_LOCATION_ID), any(List.class)))
                    .thenReturn(new BigDecimal("95"));

            assertThatThrownBy(() -> service.validateLocationCapacity(DESTINATION_LOCATION_ID, 20))
                    .isInstanceOf(LocationAtCapacityException.class);
        }
    }

    @Nested
    @DisplayName("validateSourceOnHand")
    class ValidateSourceOnHand {

        @Test
        void acceptsASourceHoldingEnoughStock() {
            assertThat(service.validateSourceOnHand(SOURCE_LOCATION_ID, SKU, 10).isValid())
                    .isTrue();
        }

        @Test
        void refusesASourceWithNothingOnHand() {
            when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                            eq(SKU), eq(SOURCE_LOCATION_ID), any(List.class)))
                    .thenReturn(new BigDecimal("0"));

            assertThatThrownBy(() -> service.validateSourceOnHand(SOURCE_LOCATION_ID, SKU, 10))
                    .isInstanceOf(NoOnHandAtSourceLocationException.class);
        }

        @Test
        void reportsAShortfallRatherThanThrowing() {
            when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                            eq(SKU), eq(SOURCE_LOCATION_ID), any(List.class)))
                    .thenReturn(new BigDecimal("4"));

            ValidationResult result = service.validateSourceOnHand(SOURCE_LOCATION_ID, SKU, 10);

            assertThat(result.isValid()).isFalse();
            assertThat(errorCodes(result)).containsExactly("INSUFFICIENT_QUANTITY");
        }
    }

    @Nested
    @DisplayName("validatePutawayExecution")
    class ValidatePutawayExecution {

        @Test
        void acceptsAStraightforwardPutaway() {
            ValidationResult result = service.validatePutawayExecution(request(10));

            assertThat(result.isValid()).isTrue();
            assertThat(result.getWarnings()).isEmpty();
        }

        @Test
        void collectsASourceShortfallAsAnError() {
            when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                            eq(SKU), eq(SOURCE_LOCATION_ID), any(List.class)))
                    .thenReturn(new BigDecimal("4"));

            ValidationResult result = service.validatePutawayExecution(request(10));

            assertThat(errorCodes(result)).contains("INSUFFICIENT_QUANTITY");
        }

        @Test
        void refusesACompatibilityOverrideWithoutThePermission() {
            PutawayExecutionRequest request = request(10);
            request.setOverrideLocationCompatibility(true);
            authenticateWith();

            assertThatThrownBy(() -> service.validatePutawayExecution(request))
                    .isInstanceOf(InsufficientPermissionException.class);
        }

        @Test
        void requiresAReasonAndJustificationOnACompatibilityOverride() {
            PutawayExecutionRequest request = request(10);
            request.setOverrideLocationCompatibility(true);
            authenticateWith(PutawayPermissions.OVERRIDE_LOCATION_COMPATIBILITY);

            ValidationResult result = service.validatePutawayExecution(request);

            assertThat(errorCodes(result))
                    .contains(
                            "COMPATIBILITY_OVERRIDE_REASON_REQUIRED", "COMPATIBILITY_OVERRIDE_JUSTIFICATION_REQUIRED");
        }

        @Test
        void recordsAnAuthorizedCompatibilityOverrideAsAWarning() {
            PutawayExecutionRequest request = request(10);
            request.setOverrideLocationCompatibility(true);
            request.setOverrideReasonCode(OverrideReasonCode.CAPACITY_OVERRIDE);
            request.setOverrideJustification("no alternative bin available");
            authenticateWith(PutawayPermissions.OVERRIDE_LOCATION_COMPATIBILITY);

            ValidationResult result = service.validatePutawayExecution(request);

            assertThat(result.isValid()).isTrue();
            assertThat(warningCodes(result)).contains("COMPATIBILITY_OVERRIDDEN");
        }

        @Test
        void turnsAMissingSourceIntoAReconciliationWarningWhenCompatibilityIsOverridden() {
            when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                            eq(SKU), eq(SOURCE_LOCATION_ID), any(List.class)))
                    .thenReturn(new BigDecimal("0"));
            PutawayExecutionRequest request = request(10);
            request.setOverrideLocationCompatibility(true);
            request.setOverrideReasonCode(OverrideReasonCode.CAPACITY_OVERRIDE);
            request.setOverrideJustification("stock physically present");
            authenticateWith(PutawayPermissions.OVERRIDE_LOCATION_COMPATIBILITY);

            ValidationResult result = service.validatePutawayExecution(request);

            assertThat(warningCodes(result)).contains("SOURCE_RECONCILIATION_NEEDED");
        }

        @Test
        void stillRefusesAMissingSourceWhenNothingIsOverridden() {
            when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                            eq(SKU), eq(SOURCE_LOCATION_ID), any(List.class)))
                    .thenReturn(new BigDecimal("0"));
            PutawayExecutionRequest request = request(10);

            assertThatThrownBy(() -> service.validatePutawayExecution(request))
                    .isInstanceOf(NoOnHandAtSourceLocationException.class);
        }

        @Test
        void refusesACapacityOverrideWithoutThePermission() {
            PutawayExecutionRequest request = request(10);
            request.setOverrideCapacity(true);
            authenticateWith();

            assertThatThrownBy(() -> service.validatePutawayExecution(request))
                    .isInstanceOf(InsufficientPermissionException.class);
        }

        @Test
        void requiresAnApproverOnACapacityOverride() {
            PutawayExecutionRequest request = request(10);
            request.setOverrideCapacity(true);
            request.setOverrideReasonCode(OverrideReasonCode.CAPACITY_OVERRIDE);
            request.setOverrideJustification("peak season");
            authenticateWith(PutawayPermissions.OVERRIDE_LOCATION_CAPACITY);

            ValidationResult result = service.validatePutawayExecution(request);

            assertThat(errorCodes(result)).contains("CAPACITY_OVERRIDE_APPROVAL_REQUIRED");
        }

        @Test
        void recordsAnAuthorizedCapacityOverrideAsAWarning() {
            PutawayExecutionRequest request = request(10);
            request.setOverrideCapacity(true);
            request.setOverrideReasonCode(OverrideReasonCode.CAPACITY_OVERRIDE);
            request.setOverrideJustification("peak season");
            request.setApprovedBy("ops.manager");
            authenticateWith(PutawayPermissions.OVERRIDE_LOCATION_CAPACITY);

            ValidationResult result = service.validatePutawayExecution(request);

            assertThat(result.isValid()).isTrue();
            assertThat(warningCodes(result)).contains("CAPACITY_OVERRIDDEN");
        }

        @Test
        void refusesACapacityOverrideThatBlowsPastTheTolerance() {
            PutawayExecutionRequest request = request(150);
            request.setOverrideCapacity(true);
            request.setOverrideReasonCode(OverrideReasonCode.CAPACITY_OVERRIDE);
            request.setOverrideJustification("peak season");
            request.setApprovedBy("ops.manager");
            authenticateWith(PutawayPermissions.OVERRIDE_LOCATION_CAPACITY);

            ValidationResult result = service.validatePutawayExecution(request);

            assertThat(errorCodes(result)).contains("CAPACITY_OVERRIDE_EXCEEDS_TOLERANCE");
        }

        @Test
        @DisplayName("#1514 - a declared zero capacity is still uncheckable for tolerance, and says so")
        void reportsThatToleranceCannotBeJudgedForADeclaredZeroCapacity() {
            when(storageLocationValidationService.getStorageLocationValidation(anyString()))
                    .thenReturn(locationValidation(true, true, 0));
            PutawayExecutionRequest request = request(10);
            request.setOverrideCapacity(true);
            request.setOverrideReasonCode(OverrideReasonCode.CAPACITY_OVERRIDE);
            request.setOverrideJustification("peak season");
            request.setApprovedBy("ops.manager");
            authenticateWith(PutawayPermissions.OVERRIDE_LOCATION_CAPACITY);

            ValidationResult result = service.validatePutawayExecution(request);

            assertThat(errorCodes(result)).contains("CAPACITY_OVERRIDE_TOLERANCE_UNCHECKABLE");
        }

        @Test
        @DisplayName("#1514 - an uncapped bin needs no tolerance judgement, so the override raises no error")
        void raisesNoToleranceErrorForAnUncappedBin() {
            // Pre-#1514 this reported CAPACITY_OVERRIDE_TOLERANCE_UNCHECKABLE, because an undeclared
            // capacity computed max = 0 and threw. An uncapped bin can no longer be over tolerance,
            // so that error code was removed along with the branch that produced it.
            when(storageLocationValidationService.getStorageLocationValidation(anyString()))
                    .thenReturn(locationValidation(true, true, null));
            PutawayExecutionRequest request = request(10);
            request.setOverrideCapacity(true);
            request.setOverrideReasonCode(OverrideReasonCode.CAPACITY_OVERRIDE);
            request.setOverrideJustification("peak season");
            request.setApprovedBy("ops.manager");
            authenticateWith(PutawayPermissions.OVERRIDE_LOCATION_CAPACITY);

            ValidationResult result = service.validatePutawayExecution(request);

            assertThat(errorCodes(result)).isEmpty();
            assertThat(warningCodes(result)).contains("CAPACITY_OVERRIDDEN");
        }

        @Test
        void carriesCapacityWarningsThroughWhenNothingIsOverridden() {
            ValidationResult result = service.validatePutawayExecution(request(95));

            assertThat(warningCodes(result)).contains("CAPACITY_NEAR_LIMIT");
        }
    }

    @Nested
    @DisplayName("no-collaborator constructor")
    class NoCollaboratorConstructor {

        @Test
        void degradesToPassThroughValidationWhenNothingIsWired() {
            PutawayValidationServiceImpl bare = new PutawayValidationServiceImpl();

            assertThat(bare.validateLocationCompatibility(DESTINATION_LOCATION_ID, SKU)
                            .isValid())
                    .isTrue();
            assertThat(bare.validateLocationCapacity(DESTINATION_LOCATION_ID, 10)
                            .isValid())
                    .isTrue();
            assertThat(bare.validateSourceOnHand(SOURCE_LOCATION_ID, SKU, 10).isValid())
                    .isTrue();
        }
    }
}
