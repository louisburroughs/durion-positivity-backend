package com.positivity.accounting.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.enums.AccountSubtype;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bean-validation tests for {@link GLAccountUpdateRequest}, covering the
 * at-least-one-field rule after the Story H1 metadata fields (accountSubtype,
 * reconcilable) were added to the update contract (Issue #934).
 */
@DisplayName("GLAccountUpdateRequest Validation Tests")
class GLAccountUpdateRequestValidationTest {

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
    @DisplayName("empty request is rejected by at-least-one-field rule")
    void emptyRequestRejected() {
        GLAccountUpdateRequest request = GLAccountUpdateRequest.builder().build();

        Set<ConstraintViolation<GLAccountUpdateRequest>> violations = validator.validate(request);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("atLeastOneFieldToUpdate"));
    }

    @Test
    @DisplayName("accountSubtype-only update passes validation")
    void subtypeOnlyUpdateValid() {
        GLAccountUpdateRequest request = GLAccountUpdateRequest.builder()
                .accountSubtype(AccountSubtype.RECEIVABLE)
                .build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("reconcilable-only update passes validation")
    void reconcilableOnlyUpdateValid() {
        GLAccountUpdateRequest request =
                GLAccountUpdateRequest.builder().reconcilable(false).build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("accountName-only update still passes validation")
    void nameOnlyUpdateValid() {
        GLAccountUpdateRequest request =
                GLAccountUpdateRequest.builder().accountName("Renamed").build();

        assertThat(validator.validate(request)).isEmpty();
    }
}
