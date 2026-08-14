package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.entity.ProductCodeType;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.repository.ProductRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Per-type product-code uniqueness guard (#1232)")
class ProductCodeUniquenessGuardTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductCodeUniquenessGuard guard;

    @Test
    void rejectsACodeAlreadyCarriedByAnotherProductOnCreate() {
        when(productRepository.existsByProductCodeTypeAndProductCode(ProductCodeType.UPC, "0123456789012"))
                .thenReturn(true);

        assertThatThrownBy(() -> guard.assertUnique(ProductCodeType.UPC, "0123456789012", null))
                .isInstanceOf(CatalogBusinessRuleException.class)
                .hasMessageContaining("UPC")
                .hasMessageContaining("0123456789012");
    }

    @Test
    void allowsAProductToKeepItsOwnCodeOnUpdate() {
        when(productRepository.existsByProductCodeTypeAndProductCodeAndIdNot(
                        ProductCodeType.UPC, "0123456789012", PRODUCT_ID))
                .thenReturn(false);

        assertThatCode(() -> guard.assertUnique(ProductCodeType.UPC, "0123456789012", PRODUCT_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsACodeHeldByADifferentProductOnUpdate() {
        when(productRepository.existsByProductCodeTypeAndProductCodeAndIdNot(
                        ProductCodeType.EAN, "3286340123456", PRODUCT_ID))
                .thenReturn(true);

        assertThatThrownBy(() -> guard.assertUnique(ProductCodeType.EAN, "3286340123456", PRODUCT_ID))
                .isInstanceOf(CatalogBusinessRuleException.class);
    }

    @Test
    void checksNothingWhenTheProductCarriesNoTypedCode() {
        assertThatCode(() -> guard.assertUnique(null, "0123456789012", null)).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertUnique(ProductCodeType.UPC, "  ", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.assertUnique(ProductCodeType.UPC, null, null))
                .doesNotThrowAnyException();

        verifyNoInteractions(productRepository);
    }

    @Test
    void comparesTheTrimmedValue() {
        when(productRepository.existsByProductCodeTypeAndProductCode(ProductCodeType.EAN, "3286340123456"))
                .thenReturn(true);

        assertThatThrownBy(() -> guard.assertUnique(ProductCodeType.EAN, " 3286340123456 ", null))
                .isInstanceOf(CatalogBusinessRuleException.class);
    }
}
