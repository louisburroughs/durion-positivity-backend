package com.positivity.workorder.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.internal.entity.EstimateItemType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The quantity-presence gate on {@link AddEstimateItemRequest} (#1569): quantity may be omitted
 * only on a LABOR line that names a serviceId — the one shape where the labor guide can prefill
 * it. Everything else must state its quantity up front.
 */
@DisplayName("AddEstimateItemRequest — quantity presence matrix (#1569)")
class AddEstimateItemRequestValidationTest {

    private static final UUID SERVICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fdd01");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fdd02");
    private static final String QUANTITY_MESSAGE =
            "quantity is required unless a LABOR item names a serviceId for guide defaulting";

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private static Set<ConstraintViolation<AddEstimateItemRequest>> quantityViolations(AddEstimateItemRequest request) {
        return validator.validate(request).stream()
                .filter(v -> QUANTITY_MESSAGE.equals(v.getMessage()))
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("LABOR with serviceId may omit quantity — the guide gets to prefill")
    void laborWithServiceIdMayOmitQuantity() {
        AddEstimateItemRequest request = AddEstimateItemRequest.builder()
                .itemType(EstimateItemType.LABOR)
                .serviceId(SERVICE_ID)
                .unitPrice(new BigDecimal("120.00"))
                .build();

        assertThat(quantityViolations(request)).isEmpty();
    }

    @Test
    @DisplayName("LABOR without serviceId must state a quantity")
    void laborWithoutServiceIdRequiresQuantity() {
        AddEstimateItemRequest request = AddEstimateItemRequest.builder()
                .itemType(EstimateItemType.LABOR)
                .description("Diagnostic")
                .unitPrice(new BigDecimal("120.00"))
                .build();

        assertThat(quantityViolations(request)).isNotEmpty();
    }

    @Test
    @DisplayName("PART must state a quantity even when it names a productId")
    void partRequiresQuantity() {
        AddEstimateItemRequest request = AddEstimateItemRequest.builder()
                .itemType(EstimateItemType.PART)
                .productId(PRODUCT_ID)
                .unitPrice(new BigDecimal("12.00"))
                .build();

        assertThat(quantityViolations(request)).isNotEmpty();
    }

    @Test
    @DisplayName("a stated quantity always satisfies the gate, whatever the shape")
    void statedQuantityAlwaysSatisfies() {
        AddEstimateItemRequest labor = AddEstimateItemRequest.builder()
                .itemType(EstimateItemType.LABOR)
                .description("Diagnostic")
                .quantity(new BigDecimal("1.5"))
                .unitPrice(new BigDecimal("120.00"))
                .build();
        AddEstimateItemRequest part = AddEstimateItemRequest.builder()
                .itemType(EstimateItemType.PART)
                .productId(PRODUCT_ID)
                .description("Brake pad set")
                .quantity(new BigDecimal("2"))
                .unitPrice(new BigDecimal("12.00"))
                .build();

        assertThat(quantityViolations(labor)).isEmpty();
        assertThat(quantityViolations(part)).isEmpty();
    }
}
