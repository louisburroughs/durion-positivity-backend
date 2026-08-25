package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.client.DocumentClient;
import com.positivity.workorder.internal.client.TaxClient;
import com.positivity.workorder.internal.dto.AddEstimateItemRequest;
import com.positivity.workorder.internal.dto.UpdateEstimateItemRequest;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.exception.FractionalQuantityNotAllowedException;
import com.positivity.workorder.internal.repository.ApprovalConfigurationRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.EstimateSnapshotRepository;
import com.positivity.workorder.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.service.BillingRulesClientService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

/**
 * The primary gate for the per-product quantity rule (ADR-0055, #1413): estimate-item entry, where
 * the quantity is authored.
 *
 * <p>{@code estimate_item.quantity} is one column shared by PART and LABOR rows, which is the whole
 * reason part quantities are fractional at all — labour needs 1.5 hours and parts inherited the
 * scale by sharing the column. So the rule has to discriminate by row type and by whether the row
 * names a catalog product, not by the column it lives in.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Estimate item quantity — per-product divisibility gate (#1413)")
class EstimateItemQuantityGateTest {

    private static final Instant NOW = Instant.parse("2026-08-19T09:00:00Z");
    private static final UUID ESTIMATE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbb01");
    private static final UUID ITEM_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbb02");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbb03");
    private static final UUID SERVICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbb04");
    private static final String PART_LABEL = "Valvoline MaxLife ATF 1qt";

    @Mock
    private EstimateRepository estimateRepository;

    @Mock
    private EstimateItemRepository estimateItemRepository;

    @Mock
    private EstimateSnapshotRepository estimateSnapshotRepository;

    @Mock
    private ApprovalConfigurationRepository approvalConfigurationRepository;

    @Mock
    private WorkorderRepository workOrderRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private BillingRulesClientService billingRulesClientService;

    @Mock
    private TaxClient taxClient;

    @Mock
    private LocationReferenceService locationReferenceService;

    @Mock
    private PeopleAvailabilityLocalService peopleAvailabilityLocalService;

    @Mock
    private DocumentClient documentClient;

    @Mock
    private CustomerReferenceService customerReferenceService;

    @Mock
    private VehicleReferenceService vehicleReferenceService;

    @Mock
    private EstimateFactPublisher estimateFactPublisher;

    @Mock
    private ExtProductUomReplicaRepository productUomRepository;

    private EstimateServiceImpl service;

    @BeforeEach
    void setUp() {
        // The platform's actual state: product_uom is unpopulated, so every product resolves to
        // whole units. Individual cases override this to declare a divisible product.
        when(productUomRepository.findBasePrecisionScales(any())).thenReturn(List.of());

        service = new EstimateServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                estimateRepository,
                estimateItemRepository,
                estimateSnapshotRepository,
                approvalConfigurationRepository,
                workOrderRepository,
                eventPublisher,
                billingRulesClientService,
                taxClient,
                locationReferenceService,
                peopleAvailabilityLocalService,
                documentClient,
                new ObjectMapper(),
                customerReferenceService,
                vehicleReferenceService,
                estimateFactPublisher,
                new PartQuantityDivisibilityService(productUomRepository));

        Estimate estimate = new Estimate();
        estimate.setId(ESTIMATE_ID);
        estimate.setStatus(EstimateStatus.DRAFT);
        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(estimate));
        when(estimateItemRepository.save(any(EstimateItem.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Nested
    @DisplayName("Adding an item")
    class Adding {

        @Test
        @DisplayName("rejects a fractional quantity for a whole-unit product")
        void rejectsFractionalPartQuantity() {
            assertThatThrownBy(() -> service.addEstimateItem(ESTIMATE_ID, partRequest("0.5"), "jane.smith"))
                    .isInstanceOf(FractionalQuantityNotAllowedException.class)
                    .hasMessageContaining(PART_LABEL)
                    .hasMessageContaining("0.5")
                    .hasMessageContaining("Enter 1 to issue and bill the full container.");

            verify(estimateItemRepository, never()).save(any());
        }

        @Test
        @DisplayName("accepts a whole quantity for a whole-unit product")
        void acceptsWholePartQuantity() {
            assertThatCode(() -> service.addEstimateItem(ESTIMATE_ID, partRequest("2"), "jane.smith"))
                    .doesNotThrowAnyException();

            verify(estimateItemRepository).save(any());
        }

        @Test
        @DisplayName("accepts a quantity within a divisible product's declared scale and rejects a finer one")
        void honoursDeclaredScale() {
            when(productUomRepository.findBasePrecisionScales(PRODUCT_ID)).thenReturn(List.of(2));

            assertThatCode(() -> service.addEstimateItem(ESTIMATE_ID, partRequest("1.01"), "jane.smith"))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> service.addEstimateItem(ESTIMATE_ID, partRequest("1.001"), "jane.smith"))
                    .isInstanceOf(FractionalQuantityNotAllowedException.class)
                    .hasMessageContaining("2 decimal places");
        }

        @Test
        @DisplayName("leaves a PART line carrying no product alone — shop supplies are not catalog stock")
        void exemptsPartWithoutProduct() {
            AddEstimateItemRequest request = partRequest("0.25");
            request.setProductId(null);
            request.setDescription("Shop supplies");

            assertThatCode(() -> service.addEstimateItem(ESTIMATE_ID, request, "jane.smith"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a whitespace-only uomCode behaves identically to an absent one and persists null")
        void blankUomCode_isTreatedAsAbsentAndPersistsNull() {
            AddEstimateItemRequest request = partRequest("2");
            request.setUomCode("   ");

            assertThatCode(() -> service.addEstimateItem(ESTIMATE_ID, request, "jane.smith"))
                    .doesNotThrowAnyException();

            org.mockito.ArgumentCaptor<EstimateItem> captor = org.mockito.ArgumentCaptor.forClass(EstimateItem.class);
            verify(estimateItemRepository).save(captor.capture());
            assertThat(captor.getValue().getUomCode()).isNull();
            verify(productUomRepository, never()).findByProductIdAndUomCode(any(), any());
        }

        @Test
        @DisplayName("leaves a fractional LABOR line alone")
        void exemptsLaborLine() {
            AddEstimateItemRequest request = AddEstimateItemRequest.builder()
                    .itemType(EstimateItemType.LABOR)
                    .description("Diagnostic")
                    .quantity(new BigDecimal("1.5"))
                    .unitPrice(new BigDecimal("120.00"))
                    .serviceId(SERVICE_ID)
                    .build();

            assertThatCode(() -> service.addEstimateItem(ESTIMATE_ID, request, "jane.smith"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Updating an item")
    class Updating {

        @Test
        @DisplayName("rejects a revision that makes a part quantity fractional")
        void rejectsFractionalRevision() {
            givenExistingItem(EstimateItemType.PART, PRODUCT_ID);

            // Without this the rule would be one PATCH away from being bypassed.
            assertThatThrownBy(() -> service.updateEstimateItem(
                            ESTIMATE_ID,
                            ITEM_ID,
                            new UpdateEstimateItemRequest(null, new BigDecimal("0.5"), null, null, null)))
                    .isInstanceOf(FractionalQuantityNotAllowedException.class);
        }

        @Test
        @DisplayName("allows a revision that leaves the quantity alone")
        void allowsDescriptionOnlyRevision() {
            EstimateItem item = givenExistingItem(EstimateItemType.PART, PRODUCT_ID);

            service.updateEstimateItem(
                    ESTIMATE_ID, ITEM_ID, new UpdateEstimateItemRequest("Front pads", null, null, null, null));

            assertThat(item.getDescription()).isEqualTo("Front pads");
        }

        @Test
        @DisplayName("leaves a fractional LABOR revision alone")
        void allowsFractionalLaborRevision() {
            givenExistingItem(EstimateItemType.LABOR, null);

            assertThatCode(() -> service.updateEstimateItem(
                            ESTIMATE_ID,
                            ITEM_ID,
                            new UpdateEstimateItemRequest(null, new BigDecimal("1.5"), null, null, null)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("revising quantity together with description re-validates using the new description")
        void revisingQuantityWithDescriptionUsesNewDescriptionInGateMessage() {
            // The gate call reads request.getDescription() when the PATCH supplies one,
            // falling back to the item's stored description only when it doesn't — so a
            // combined description+quantity revision must name the NEW description in
            // the rejection, not the stale one still on the entity.
            givenExistingItem(EstimateItemType.PART, PRODUCT_ID);

            assertThatThrownBy(() -> service.updateEstimateItem(
                            ESTIMATE_ID,
                            ITEM_ID,
                            new UpdateEstimateItemRequest("Rear brake pads", new BigDecimal("0.5"), null, null, null)))
                    .isInstanceOf(FractionalQuantityNotAllowedException.class)
                    .hasMessageContaining("Rear brake pads");
        }

        @Test
        @DisplayName("re-pointing an unchanged quantity at a new uomCode still clears the gate")
        void rejectsUomCodeOnlyRevisionThatMakesTheExistingQuantityFractional() {
            // The existing item carries quantity=1 (base EA, scale 0). Re-pointing it at a unit
            // whose factor turns 1 into a fraction must be caught even though the quantity number
            // itself never changes on this request (ADR-0055 stage 3, #1415).
            givenExistingItem(EstimateItemType.PART, PRODUCT_ID);
            when(productUomRepository.findByProductIdAndUomCode(PRODUCT_ID, "PAIR"))
                    .thenReturn(Optional.of(com.positivity.workorder.internal.entity.ExtProductUomReplica.builder()
                            .productId(PRODUCT_ID)
                            .uomCode("PAIR")
                            .uomType("PACK")
                            .factorToBase(new BigDecimal("0.5"))
                            .precisionScale(0)
                            .aggregateVersion(1L)
                            .build()));

            assertThatThrownBy(() -> service.updateEstimateItem(
                            ESTIMATE_ID, ITEM_ID, new UpdateEstimateItemRequest(null, null, null, null, "PAIR")))
                    .isInstanceOf(FractionalQuantityNotAllowedException.class)
                    .hasMessageContaining("0.5");
        }

        @Test
        @DisplayName("a whitespace-only uomCode revision behaves identically to an absent one and clears to base unit")
        void blankUomCodeRevision_clearsToBaseUnit() {
            EstimateItem item = givenExistingItem(EstimateItemType.PART, PRODUCT_ID);
            item.setUomCode("QT");

            assertThatCode(() -> service.updateEstimateItem(
                            ESTIMATE_ID, ITEM_ID, new UpdateEstimateItemRequest(null, null, null, null, "   ")))
                    .doesNotThrowAnyException();

            assertThat(item.getUomCode()).isNull();
            verify(productUomRepository, never()).findByProductIdAndUomCode(any(), any());
        }
    }

    @Nested
    @DisplayName("uomCode on LABOR (ADR-0055 stage 3, #1415)")
    class LaborUomRejection {

        @Test
        @DisplayName("rejects a non-null uomCode on a LABOR item at add time")
        void rejectsUomCodeOnLaborAdd() {
            AddEstimateItemRequest request = AddEstimateItemRequest.builder()
                    .itemType(EstimateItemType.LABOR)
                    .description("Diagnostic")
                    .quantity(new BigDecimal("1.5"))
                    .unitPrice(new BigDecimal("120.00"))
                    .serviceId(SERVICE_ID)
                    .uomCode("HR")
                    .build();

            assertThatThrownBy(() -> service.addEstimateItem(ESTIMATE_ID, request, "jane.smith"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("LABOR");

            verify(estimateItemRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects revising a LABOR item to carry a uomCode")
        void rejectsUomCodeOnLaborUpdate() {
            givenExistingItem(EstimateItemType.LABOR, null);

            assertThatThrownBy(() -> service.updateEstimateItem(
                            ESTIMATE_ID, ITEM_ID, new UpdateEstimateItemRequest(null, null, null, null, "HR")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("LABOR");
        }
    }

    @Nested
    @DisplayName("uomCode conversion at the gate (ADR-0055 stage 3, #1415)")
    class UomConversionGate {

        @Test
        @DisplayName("omitting uomCode behaves exactly as today (base unit assumed)")
        void absentUomCodeIsBaseUnit() {
            assertThatCode(() -> service.addEstimateItem(ESTIMATE_ID, partRequest("2"), "jane.smith"))
                    .doesNotThrowAnyException();

            verify(productUomRepository, never()).findByProductIdAndUomCode(any(), any());
        }

        @Test
        @DisplayName(
                "gate composition: 4.5 QT at factor 0.25 (BASE=EA, scale 0) converts to 1.125 EA and is " + "rejected")
        void rejectsConvertedFractionAtWholeUnitScale() {
            when(productUomRepository.findByProductIdAndUomCode(PRODUCT_ID, "QT"))
                    .thenReturn(Optional.of(com.positivity.workorder.internal.entity.ExtProductUomReplica.builder()
                            .productId(PRODUCT_ID)
                            .uomCode("QT")
                            .uomType("PACK")
                            .factorToBase(new BigDecimal("0.25"))
                            .precisionScale(0)
                            .aggregateVersion(1L)
                            .build()));

            AddEstimateItemRequest request = partRequest("4.5");
            request.setUomCode("QT");

            assertThatThrownBy(() -> service.addEstimateItem(ESTIMATE_ID, request, "jane.smith"))
                    .isInstanceOf(FractionalQuantityNotAllowedException.class)
                    .hasMessageContaining("1.125");

            verify(estimateItemRepository, never()).save(any());
        }

        @Test
        @DisplayName("gate composition: a scale-2 product accepts the converted value")
        void acceptsConvertedValueWithinDeclaredScale() {
            when(productUomRepository.findByProductIdAndUomCode(PRODUCT_ID, "BAG"))
                    .thenReturn(Optional.of(com.positivity.workorder.internal.entity.ExtProductUomReplica.builder()
                            .productId(PRODUCT_ID)
                            .uomCode("BAG")
                            .uomType("PACK")
                            .factorToBase(new BigDecimal("1.01"))
                            .precisionScale(2)
                            .aggregateVersion(1L)
                            .build()));
            when(productUomRepository.findBasePrecisionScales(PRODUCT_ID)).thenReturn(List.of(2));

            AddEstimateItemRequest request = partRequest("1");
            request.setUomCode("BAG");

            assertThatCode(() -> service.addEstimateItem(ESTIMATE_ID, request, "jane.smith"))
                    .doesNotThrowAnyException();

            verify(estimateItemRepository).save(any());
        }
    }

    private AddEstimateItemRequest partRequest(String quantity) {
        return AddEstimateItemRequest.builder()
                .itemType(EstimateItemType.PART)
                .description(PART_LABEL)
                .quantity(new BigDecimal(quantity))
                .unitPrice(new BigDecimal("12.00"))
                .productId(PRODUCT_ID)
                .build();
    }

    private EstimateItem givenExistingItem(EstimateItemType type, UUID productId) {
        Estimate estimate = new Estimate();
        estimate.setId(ESTIMATE_ID);
        EstimateItem item = EstimateItem.builder()
                .id(ITEM_ID)
                .estimate(estimate)
                .itemType(type)
                .description(type == EstimateItemType.PART ? PART_LABEL : "Diagnostic")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("12.00"))
                .productId(productId)
                .serviceId(type == EstimateItemType.LABOR ? SERVICE_ID : null)
                .build();
        when(estimateItemRepository.findByIdAndEstimate_IdAndDeletedFalse(ITEM_ID, ESTIMATE_ID))
                .thenReturn(Optional.of(item));
        return item;
    }
}
