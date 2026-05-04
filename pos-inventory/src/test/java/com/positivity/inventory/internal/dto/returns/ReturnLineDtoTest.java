package com.positivity.inventory.internal.dto.returns;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReturnLineDto validation constraints (T-008)")
class ReturnLineDtoTest {

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

    @Test
    @DisplayName("null itemId produces a constraint violation on itemId field")
    void nullItemId_producesConstraintViolation() {
        ReturnLineDto dto = ReturnLineDto.builder()
                .itemId(null)
                .quantity(1)
                .reasonCode("DAMAGED")
                .locationId(UUID.randomUUID())
                .build();

        Set<ConstraintViolation<ReturnLineDto>> violations = validator.validate(dto);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("itemId");
    }

    @Test
    @DisplayName("quantity = 0 produces a constraint violation on quantity field")
    void zeroQuantity_producesConstraintViolation() {
        ReturnLineDto dto = ReturnLineDto.builder()
                .itemId(UUID.randomUUID())
                .quantity(0)
                .reasonCode("DAMAGED")
                .locationId(UUID.randomUUID())
                .build();

        Set<ConstraintViolation<ReturnLineDto>> violations = validator.validate(dto);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("quantity");
    }

    @Test
    @DisplayName("quantity = -1 produces a constraint violation on quantity field")
    void negativeQuantity_producesConstraintViolation() {
        ReturnLineDto dto = ReturnLineDto.builder()
                .itemId(UUID.randomUUID())
                .quantity(-1)
                .reasonCode("DAMAGED")
                .locationId(UUID.randomUUID())
                .build();

        Set<ConstraintViolation<ReturnLineDto>> violations = validator.validate(dto);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("quantity");
    }

    @Test
    @DisplayName("valid itemId and positive quantity produces no constraint violations")
    void validItemIdAndPositiveQuantity_noViolations() {
        ReturnLineDto dto = ReturnLineDto.builder()
                .itemId(UUID.randomUUID())
                .quantity(1)
                .reasonCode("DAMAGED")
                .locationId(UUID.randomUUID())
                .build();

        Set<ConstraintViolation<ReturnLineDto>> violations = validator.validate(dto);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("itemId", "quantity");
    }
}
