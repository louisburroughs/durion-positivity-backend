package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.config.CatalogFactPublisher;
import com.positivity.catalog.internal.dto.ProductCreateRequestDto;
import com.positivity.catalog.internal.dto.ProductUpdateRequestDto;
import com.positivity.catalog.internal.entity.Category;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.Subcategory;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.CategoryRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.SubcategoryRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Covers the subcategory writer added for issue #1514: before this, {@code ProductEntity.subcategory}
 * had no writer anywhere in pos-catalog, so an API-created product published a null subcategory
 * forever and SUBCATEGORY-precedence putaway silently degraded to category-only matching.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductMasterDataServiceImplSubcategoryTest {

    private static final UUID ELECTRICAL_SYSTEM_ID = UUID.fromString("01960030-0000-7000-8000-000000000004");
    private static final UUID BATTERIES_ID = UUID.fromString("01960031-0000-7000-8000-00000000000e");
    private static final UUID PRODUCT_ID = UUID.fromString("01960032-0000-7000-8000-000000000001");

    @Mock
    ProductRepository productRepository;

    @Mock
    CategoryRepository categoryRepository;

    @Mock
    SubcategoryRepository subcategoryRepository;

    @Mock
    ProductDetailCacheInvalidationPublisher productDetailCacheInvalidationPublisher;

    @Mock
    CatalogFactPublisher catalogFactPublisher;

    @Mock
    ProductCodeUniquenessGuard productCodeUniquenessGuard;

    @InjectMocks
    ProductMasterDataServiceImpl service;

    private Category electricalSystem;
    private Subcategory batteries;

    @BeforeEach
    void setUp() {
        electricalSystem = new Category();
        electricalSystem.setId(ELECTRICAL_SYSTEM_ID);
        electricalSystem.setName("Electrical System");

        batteries = new Subcategory();
        batteries.setId(BATTERIES_ID);
        batteries.setName("Batteries");

        when(categoryRepository.findById(ELECTRICAL_SYSTEM_ID)).thenReturn(Optional.of(electricalSystem));
        when(subcategoryRepository.findById(BATTERIES_ID)).thenReturn(Optional.of(batteries));
        when(productRepository.save(any(ProductEntity.class))).thenAnswer(invocation -> {
            ProductEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(PRODUCT_ID);
            }
            return entity;
        });
    }

    private ProductCreateRequestDto createRequest() {
        ProductCreateRequestDto request = new ProductCreateRequestDto();
        request.setName("Group 31 AGM Battery");
        request.setDescription("Heavy duty AGM battery");
        request.setUnitOfMeasure("EA");
        request.setSku("MOT-BAT-31");
        request.setMpn("BAT-31-AGM");
        return request;
    }

    // ─── create ───────────────────────────────────────────────────────────────

    @Test
    void createProduct_persistsAndReturnsSubcategory() {
        ProductCreateRequestDto request = createRequest();
        request.setCategoryId(ELECTRICAL_SYSTEM_ID);
        request.setSubcategoryId(BATTERIES_ID);

        var dto = service.createProduct(request);

        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        org.mockito.Mockito.verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getSubcategory()).isSameAs(batteries);
        assertThat(captor.getValue().getCategory()).isSameAs(electricalSystem);

        assertThat(dto.getSubcategory()).isNotNull();
        assertThat(dto.getSubcategory().getId()).isEqualTo(BATTERIES_ID);
        assertThat(dto.getSubcategory().getName()).isEqualTo("Batteries");
        assertThat(dto.getCategory().getId()).isEqualTo(ELECTRICAL_SYSTEM_ID);
    }

    @Test
    void createProduct_publishesFactWithARealSubcategoryToCarry() {
        ProductCreateRequestDto request = createRequest();
        request.setCategoryId(ELECTRICAL_SYSTEM_ID);
        request.setSubcategoryId(BATTERIES_ID);

        service.createProduct(request);

        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        org.mockito.Mockito.verify(catalogFactPublisher).publishProductUpdated(captor.capture());
        assertThat(captor.getValue().getSubcategory()).isNotNull();
        assertThat(captor.getValue().getSubcategory().getId()).isEqualTo(BATTERIES_ID);
    }

    @Test
    void createProduct_withoutSubcategoryId_leavesSubcategoryNull() {
        ProductCreateRequestDto request = createRequest();
        request.setCategoryId(ELECTRICAL_SYSTEM_ID);

        var dto = service.createProduct(request);

        assertThat(dto.getSubcategory()).isNull();
    }

    @Test
    void createProduct_unresolvableSubcategoryId_throws() {
        UUID missing = UUID.fromString("01960031-0000-7000-8000-0000000000ff");
        when(subcategoryRepository.findById(missing)).thenReturn(Optional.empty());

        ProductCreateRequestDto request = createRequest();
        request.setSubcategoryId(missing);

        assertThatThrownBy(() -> service.createProduct(request))
                .isInstanceOf(CatalogValidationException.class)
                .hasMessageContaining("Subcategory not found");
    }

    // ─── update ───────────────────────────────────────────────────────────────

    @Test
    void updateProduct_roundTripsSubcategory() {
        ProductEntity existing = new ProductEntity();
        existing.setId(PRODUCT_ID);
        existing.setSku("MOT-BAT-31");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));

        ProductUpdateRequestDto request = new ProductUpdateRequestDto();
        request.setName("Group 31 AGM Battery");
        request.setDescription("Heavy duty AGM battery");
        request.setUnitOfMeasure("EA");
        request.setMpn("BAT-31-AGM");
        request.setCategoryId(ELECTRICAL_SYSTEM_ID);
        request.setSubcategoryId(BATTERIES_ID);

        var dto = service.updateProduct(PRODUCT_ID, request);

        assertThat(existing.getSubcategory()).isSameAs(batteries);
        assertThat(dto.getSubcategory()).isNotNull();
        assertThat(dto.getSubcategory().getId()).isEqualTo(BATTERIES_ID);
    }

    @Test
    void updateProduct_omittedSubcategoryId_clearsIt() {
        ProductEntity existing = new ProductEntity();
        existing.setId(PRODUCT_ID);
        existing.setSku("MOT-BAT-31");
        existing.setSubcategory(batteries);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));

        ProductUpdateRequestDto request = new ProductUpdateRequestDto();
        request.setName("Group 31 AGM Battery");
        request.setDescription("Heavy duty AGM battery");
        request.setUnitOfMeasure("EA");
        request.setMpn("BAT-31-AGM");

        var dto = service.updateProduct(PRODUCT_ID, request);

        assertThat(existing.getSubcategory()).isNull();
        assertThat(dto.getSubcategory()).isNull();
    }
}
