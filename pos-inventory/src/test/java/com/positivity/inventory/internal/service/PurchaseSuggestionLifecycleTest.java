package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsRequest;
import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsResponse;
import com.positivity.inventory.internal.dto.purchasesuggestion.PurchaseSuggestionResponse;
import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.ExtProductUomReplica;
import com.positivity.inventory.internal.entity.PurchaseSuggestion;
import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import com.positivity.inventory.internal.enums.SuggestedVendorType;
import com.positivity.inventory.internal.exception.PurchaseSuggestionConversionException;
import com.positivity.inventory.internal.exception.PurchaseSuggestionStateException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.inventory.internal.repository.PurchaseSuggestionRepository;
import com.positivity.inventory.service.PurchaseSuggestionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Lifecycle and conversion-precondition tests for purchase suggestions (odoo-parity F4,
 * issue #1044; plan D-3): accept/dismiss transitions, single-vendor multi-line convert,
 * mixed-vendor rejection, and the B2 pack-UoM expression on converted PO lines.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Purchase suggestion lifecycle and conversion (F4)")
class PurchaseSuggestionLifecycleTest {

    private static final String ACTOR = "f4-lifecycle-test";

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private PurchaseOrderCommandPublisher purchaseOrderCommandPublisher;

    @Autowired
    private PurchaseSuggestionService purchaseSuggestionService;

    @Autowired
    private PurchaseSuggestionRepository suggestionRepository;

    @Autowired
    private ExtProductReplicaRepository extProductReplicaRepository;

    @Autowired
    private ExtProductUomReplicaRepository extProductUomReplicaRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.BeforeEach
    void createPoNumberSequence() {
        // Flyway is disabled under the test profile (ddl-auto), so the V2 sequence used by
        // PO number generation must exist explicitly (same pattern as the PO contract ITs).
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS purchase_order_number_seq START WITH 1 INCREMENT BY 1");
    }

    private PurchaseSuggestion seedSuggestion(
            PurchaseSuggestionStatus status, UUID vendorRefId, Long unitCostMinor, UUID locationId) {
        return seedSuggestion(status, vendorRefId, unitCostMinor, locationId, UUID.randomUUID(), 12, null);
    }

    private PurchaseSuggestion seedSuggestion(
            PurchaseSuggestionStatus status,
            UUID vendorRefId,
            Long unitCostMinor,
            UUID locationId,
            UUID productId,
            int quantity,
            Integer vendorPackSize) {
        return suggestionRepository.save(PurchaseSuggestion.builder()
                .policyId(UUID.randomUUID())
                .itemSKU(productId.toString())
                .locationId(locationId)
                .suggestedQuantity(quantity)
                .suggestedVendorType(vendorRefId != null ? SuggestedVendorType.MANUFACTURER : null)
                .vendorRefId(vendorRefId)
                .unitCostMinor(unitCostMinor)
                .unitCostCurrency(unitCostMinor != null ? "USD" : null)
                .vendorPackSize(vendorPackSize)
                .leadTimeDays(5)
                .earliestExpectedDate(LocalDate.now().plusDays(5))
                .selectionReason("seeded for lifecycle test")
                .status(status)
                .build());
    }

    // ─── Accept / dismiss lifecycle ──────────────────────────────────────────

    @Test
    @DisplayName("accept transitions SUGGESTED → ACCEPTED; accepting again conflicts")
    void accept_lifecycle() {
        PurchaseSuggestion suggestion =
                seedSuggestion(PurchaseSuggestionStatus.SUGGESTED, UUID.randomUUID(), 499L, UUID.randomUUID());

        PurchaseSuggestionResponse accepted =
                purchaseSuggestionService.acceptPurchaseSuggestion(suggestion.getSuggestionId(), ACTOR);
        assertThat(accepted.getStatus()).isEqualTo("ACCEPTED");

        assertThatThrownBy(
                        () -> purchaseSuggestionService.acceptPurchaseSuggestion(suggestion.getSuggestionId(), ACTOR))
                .isInstanceOf(PurchaseSuggestionStateException.class);
    }

    @Test
    @DisplayName("dismiss records the mandatory reason from SUGGESTED or ACCEPTED; terminal states conflict")
    void dismiss_lifecycle() {
        PurchaseSuggestion suggested =
                seedSuggestion(PurchaseSuggestionStatus.SUGGESTED, UUID.randomUUID(), 499L, UUID.randomUUID());
        PurchaseSuggestionResponse dismissed = purchaseSuggestionService.dismissPurchaseSuggestion(
                suggested.getSuggestionId(), "vendor under renegotiation", ACTOR);
        assertThat(dismissed.getStatus()).isEqualTo("DISMISSED");
        assertThat(dismissed.getDismissalReason()).isEqualTo("vendor under renegotiation");

        PurchaseSuggestion acceptedRow =
                seedSuggestion(PurchaseSuggestionStatus.ACCEPTED, UUID.randomUUID(), 499L, UUID.randomUUID());
        assertThat(purchaseSuggestionService
                        .dismissPurchaseSuggestion(acceptedRow.getSuggestionId(), "changed plans", ACTOR)
                        .getStatus())
                .isEqualTo("DISMISSED");

        assertThatThrownBy(() -> purchaseSuggestionService.dismissPurchaseSuggestion(
                        suggested.getSuggestionId(), "again", ACTOR))
                .isInstanceOf(PurchaseSuggestionStateException.class);
    }

    @Test
    @DisplayName("get returns the suggestion; unknown ids are 404s")
    void getAndList() {
        PurchaseSuggestion suggestion =
                seedSuggestion(PurchaseSuggestionStatus.SUGGESTED, UUID.randomUUID(), 499L, UUID.randomUUID());

        assertThat(purchaseSuggestionService
                        .getPurchaseSuggestion(suggestion.getSuggestionId())
                        .getSuggestionId())
                .isEqualTo(suggestion.getSuggestionId());
        UUID unknownId = UUID.randomUUID();
        assertThatThrownBy(() -> purchaseSuggestionService.getPurchaseSuggestion(unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ─── Conversion ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("multiple ACCEPTED suggestions for one vendor convert into ONE multi-line DRAFT PO")
    void convert_multiSuggestionSingleVendor() {
        UUID vendorId = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        PurchaseSuggestion first = seedSuggestion(PurchaseSuggestionStatus.ACCEPTED, vendorId, 499L, location);
        PurchaseSuggestion second = seedSuggestion(PurchaseSuggestionStatus.ACCEPTED, vendorId, 799L, location);

        ConvertPurchaseSuggestionsResponse converted = purchaseSuggestionService.convertPurchaseSuggestions(
                new ConvertPurchaseSuggestionsRequest(List.of(first.getSuggestionId(), second.getSuggestionId())),
                ACTOR);

        // The order is placed by pos-order, so what is known here is that it was asked for and
        // at what identity — not the number or status pos-order will assign (CAP-320 #1334).
        assertThat(converted.getPurchaseOrderStatus()).isEqualTo("REQUESTED");
        assertThat(converted.getPurchaseOrderId()).isNotNull();
        assertThat(converted.getPoNumber()).isNull();
        assertThat(converted.getConvertedSuggestionIds())
                .containsExactly(first.getSuggestionId(), second.getSuggestionId());

        assertThat(suggestionRepository
                        .findById(first.getSuggestionId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(PurchaseSuggestionStatus.CONVERTED);
        assertThat(suggestionRepository
                        .findById(second.getSuggestionId())
                        .orElseThrow()
                        .getConvertedPurchaseOrderId())
                .isEqualTo(converted.getPurchaseOrderId());
    }

    @Test
    @DisplayName("mixed vendors in one convert request are rejected with 422 VENDOR_MISMATCH")
    void convert_mixedVendorsRejected() {
        UUID location = UUID.randomUUID();
        PurchaseSuggestion first = seedSuggestion(PurchaseSuggestionStatus.ACCEPTED, UUID.randomUUID(), 499L, location);
        PurchaseSuggestion second =
                seedSuggestion(PurchaseSuggestionStatus.ACCEPTED, UUID.randomUUID(), 799L, location);

        ConvertPurchaseSuggestionsRequest request =
                new ConvertPurchaseSuggestionsRequest(List.of(first.getSuggestionId(), second.getSuggestionId()));
        assertThatThrownBy(() -> purchaseSuggestionService.convertPurchaseSuggestions(request, ACTOR))
                .isInstanceOf(PurchaseSuggestionConversionException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PURCHASE_SUGGESTION_VENDOR_MISMATCH");
        // Nothing was stamped.
        assertThat(suggestionRepository
                        .findById(first.getSuggestionId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(PurchaseSuggestionStatus.ACCEPTED);
    }

    @Test
    @DisplayName("non-ACCEPTED suggestions cannot convert (D-3: human accept precedes conversion)")
    void convert_requiresAcceptedStatus() {
        PurchaseSuggestion suggested =
                seedSuggestion(PurchaseSuggestionStatus.SUGGESTED, UUID.randomUUID(), 499L, UUID.randomUUID());

        ConvertPurchaseSuggestionsRequest request =
                new ConvertPurchaseSuggestionsRequest(List.of(suggested.getSuggestionId()));
        assertThatThrownBy(() -> purchaseSuggestionService.convertPurchaseSuggestions(request, ACTOR))
                .isInstanceOf(PurchaseSuggestionConversionException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PURCHASE_SUGGESTION_NOT_ACCEPTED");
    }

    @Test
    @DisplayName("a vendor-less or priceless suggestion cannot convert — guided 422s")
    void convert_requiresVendorAndPrice() {
        PurchaseSuggestion vendorless =
                seedSuggestion(PurchaseSuggestionStatus.ACCEPTED, null, null, UUID.randomUUID());
        ConvertPurchaseSuggestionsRequest vendorlessRequest =
                new ConvertPurchaseSuggestionsRequest(List.of(vendorless.getSuggestionId()));
        assertThatThrownBy(() -> purchaseSuggestionService.convertPurchaseSuggestions(vendorlessRequest, ACTOR))
                .isInstanceOf(PurchaseSuggestionConversionException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PURCHASE_SUGGESTION_MISSING_VENDOR");

        PurchaseSuggestion priceless =
                seedSuggestion(PurchaseSuggestionStatus.ACCEPTED, UUID.randomUUID(), null, UUID.randomUUID());
        ConvertPurchaseSuggestionsRequest pricelessRequest =
                new ConvertPurchaseSuggestionsRequest(List.of(priceless.getSuggestionId()));
        assertThatThrownBy(() -> purchaseSuggestionService.convertPurchaseSuggestions(pricelessRequest, ACTOR))
                .isInstanceOf(PurchaseSuggestionConversionException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PURCHASE_SUGGESTION_MISSING_UNIT_COST");
    }

    @Test
    @DisplayName("pack UoM expression (B2): a PACK-type replica row matching the vendor pack size keys the"
            + " PO line in packs with a per-pack unit cost")
    void convert_expressesPackUomWhenReplicaMatches() {
        UUID vendorId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        // Catalog replica: base EA (scale 0) + CASE6 pack row with factor 6.
        extProductReplicaRepository.save(ExtProductReplica.builder()
                .productId(productId)
                .baseUom("EA")
                .trackingLevel("NONE")
                .aggregateVersion(1L)
                .updatedAt(Instant.now())
                .build());
        extProductUomReplicaRepository.save(ExtProductUomReplica.builder()
                .productId(productId)
                .uomCode("EA")
                .uomType("BASE")
                .factorToBase(BigDecimal.ONE)
                .precisionScale(0)
                .build());
        extProductUomReplicaRepository.save(ExtProductUomReplica.builder()
                .productId(productId)
                .uomCode("CASE6")
                .uomType("PACK")
                .factorToBase(new BigDecimal("6"))
                .precisionScale(0)
                .build());
        // Suggestion: 24 units at 499 minor/unit, vendor pack size 6 → 4 CASE6 at 2994 minor/case.
        PurchaseSuggestion suggestion =
                seedSuggestion(PurchaseSuggestionStatus.ACCEPTED, vendorId, 499L, location, productId, 24, 6);

        ConvertPurchaseSuggestionsResponse converted = purchaseSuggestionService.convertPurchaseSuggestions(
                new ConvertPurchaseSuggestionsRequest(List.of(suggestion.getSuggestionId())), ACTOR);

        // What this module is responsible for is the request it raises; placing the order and
        // converting the pack quantity to base are pos-order's (CAP-320 #1334).
        var command = capturedRequest();
        assertThat(command.purchaseOrderId()).isEqualTo(converted.getPurchaseOrderId());
        assertThat(command.lines()).hasSize(1);
        var line = command.lines().getFirst();
        assertThat(line.documentUom()).isEqualTo("CASE6");
        assertThat(line.documentQuantity()).isEqualByComparingTo("4");
        // Base quantity is deliberately left unstated: the order converts it, and computing it on
        // both sides is how the two ends come to disagree.
        assertThat(line.quantity()).isNull();
        assertThat(line.unitCostMinor()).isEqualTo(2994L); // per-pack cost = 499 × 6
    }

    @Test
    @DisplayName("no matching PACK replica row: the line stays in base UoM with the per-unit cost")
    void convert_fallsBackToBaseUomWithoutPackRow() {
        UUID vendorId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        // No ext_product_uom rows at all for this product.
        PurchaseSuggestion suggestion =
                seedSuggestion(PurchaseSuggestionStatus.ACCEPTED, vendorId, 499L, UUID.randomUUID(), productId, 24, 6);

        ConvertPurchaseSuggestionsResponse converted = purchaseSuggestionService.convertPurchaseSuggestions(
                new ConvertPurchaseSuggestionsRequest(List.of(suggestion.getSuggestionId())), ACTOR);

        var line = capturedRequest().lines().getFirst();
        assertThat(line.documentUom()).isNull();
        assertThat(line.quantity()).isEqualByComparingTo("24");
        assertThat(line.unitCostMinor()).isEqualTo(499L);
    }

    /** The purchase-order request this module raised, as pos-order would receive it. */
    private com.positivity.domainevents.order.PurchaseOrderRequestedV1 capturedRequest() {
        var captor =
                org.mockito.ArgumentCaptor.forClass(com.positivity.domainevents.order.PurchaseOrderRequestedV1.class);
        org.mockito.Mockito.verify(purchaseOrderCommandPublisher).request(captor.capture());
        return captor.getValue();
    }
}
