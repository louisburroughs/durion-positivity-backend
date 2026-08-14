package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.ProductCodeDuplicate;
import com.positivity.catalog.internal.dto.ProductCodeKind;
import com.positivity.catalog.internal.dto.ProductCodeMatch;
import com.positivity.catalog.internal.entity.ProductCodeType;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Deterministic product-code lookup (#1232)")
class ProductCodeLookupServiceImplTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID OTHER_PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductCodeLookupServiceImpl service;

    private static ProductEntity product(UUID id, ProductCodeType type, String code) {
        ProductEntity entity = new ProductEntity();
        entity.setId(id);
        entity.setName("Tire 205/55R16");
        entity.setSku("SKU-" + id);
        entity.setProductCodeType(type);
        entity.setProductCode(code);
        entity.setManufacturerPartNumber("MPN-1");
        return entity;
    }

    @Nested
    @DisplayName("findByProductCode")
    class FindByProductCode {

        @Test
        void returnsTheSingleProductCarryingTheCode() {
            when(productRepository.findByProductCodeTypeAndProductCode(ProductCodeType.EAN, "3286340123456"))
                    .thenReturn(List.of(product(PRODUCT_ID, ProductCodeType.EAN, "3286340123456")));

            Optional<ProductCodeMatch> match = service.findByProductCode(ProductCodeKind.EAN, "3286340123456");

            assertThat(match).isPresent();
            assertThat(match.get().productId()).isEqualTo(PRODUCT_ID);
            assertThat(match.get().codeType()).isEqualTo(ProductCodeKind.EAN);
            assertThat(match.get().code()).isEqualTo("3286340123456");
        }

        @Test
        void trimsSurroundingWhitespaceBeforeMatching() {
            when(productRepository.findByProductCodeTypeAndProductCode(ProductCodeType.UPC, "0123456789012"))
                    .thenReturn(List.of(product(PRODUCT_ID, ProductCodeType.UPC, "0123456789012")));

            assertThat(service.findByProductCode(ProductCodeKind.UPC, "  0123456789012 "))
                    .isPresent();
        }

        @Test
        void returnsEmptyWhenNoProductCarriesTheCode() {
            when(productRepository.findByProductCodeTypeAndProductCode(ProductCodeType.EAN, "0000000000000"))
                    .thenReturn(List.of());

            assertThat(service.findByProductCode(ProductCodeKind.EAN, "0000000000000"))
                    .isEmpty();
        }

        @Test
        void treatsTheSameDigitsUnderADifferentSchemeAsADifferentCode() {
            when(productRepository.findByProductCodeTypeAndProductCode(ProductCodeType.UPC, "3286340123456"))
                    .thenReturn(List.of());

            assertThat(service.findByProductCode(ProductCodeKind.UPC, "3286340123456"))
                    .isEmpty();
        }

        @Test
        void neverQueriesForABlankCode() {
            assertThat(service.findByProductCode(ProductCodeKind.EAN, "   ")).isEmpty();
            verifyNoInteractions(productRepository);
        }

        @Test
        void refusesToPickAWinnerWhenTheCodeIsAmbiguous() {
            when(productRepository.findByProductCodeTypeAndProductCode(ProductCodeType.EAN, "3286340123456"))
                    .thenReturn(List.of(
                            product(PRODUCT_ID, ProductCodeType.EAN, "3286340123456"),
                            product(OTHER_PRODUCT_ID, ProductCodeType.EAN, "3286340123456")));

            assertThatThrownBy(() -> service.findByProductCode(ProductCodeKind.EAN, "3286340123456"))
                    .isInstanceOf(CatalogBusinessRuleException.class)
                    .hasMessageContaining("carried by 2 products");
        }
    }

    @Nested
    @DisplayName("findDuplicateProductCodes")
    class FindDuplicates {

        @Test
        void reportsEveryProductCarryingADuplicatedValue() {
            when(productRepository.findDuplicateProductCodeGroups())
                    .thenReturn(List.<Object[]>of(new Object[] {ProductCodeType.EAN, "3286340123456", 2L}));
            when(productRepository.findByProductCodeTypeAndProductCode(ProductCodeType.EAN, "3286340123456"))
                    .thenReturn(List.of(
                            product(OTHER_PRODUCT_ID, ProductCodeType.EAN, "3286340123456"),
                            product(PRODUCT_ID, ProductCodeType.EAN, "3286340123456")));

            List<ProductCodeDuplicate> duplicates = service.findDuplicateProductCodes();

            assertThat(duplicates).hasSize(1);
            assertThat(duplicates.getFirst().codeType()).isEqualTo(ProductCodeKind.EAN);
            assertThat(duplicates.getFirst().productCount()).isEqualTo(2);
            assertThat(duplicates.getFirst().productIds()).containsExactly(PRODUCT_ID, OTHER_PRODUCT_ID);
        }

        @Test
        void isEmptyOnCleanData() {
            when(productRepository.findDuplicateProductCodeGroups()).thenReturn(List.of());

            assertThat(service.findDuplicateProductCodes()).isEmpty();
        }
    }
}
