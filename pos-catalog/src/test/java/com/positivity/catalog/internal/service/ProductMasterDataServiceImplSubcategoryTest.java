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
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
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
 *
 * <p>Also covers the category/subcategory pair invariant added for issue #1536: a subcategory now declares
 * its parent category, so a request pairing a subcategory with some other category is a contradiction the
 * service rejects rather than persists, and a request supplying only a subcategory derives the parent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductMasterDataServiceImplSubcategoryTest {

    private static final UUID ELECTRICAL_SYSTEM_ID = UUID.fromString("01960030-0000-7000-8000-000000000004");
    private static final UUID TIRES_AND_WHEELS_ID = UUID.fromString("01960030-0000-7000-8000-000000000001");
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
    private Category tiresAndWheels;
    private Subcategory batteries;

    @BeforeEach
    void setUp() {
        electricalSystem = new Category();
        electricalSystem.setId(ELECTRICAL_SYSTEM_ID);
        electricalSystem.setName("Electrical System");

        tiresAndWheels = new Category();
        tiresAndWheels.setId(TIRES_AND_WHEELS_ID);
        tiresAndWheels.setName("Tires & Wheels");

        batteries = new Subcategory();
        batteries.setId(BATTERIES_ID);
        batteries.setName("Batteries");
        // #1536: subcategory.category is NOT NULL — Batteries lives under Electrical System.
        batteries.setCategory(electricalSystem);

        when(categoryRepository.findById(ELECTRICAL_SYSTEM_ID)).thenReturn(Optional.of(electricalSystem));
        when(categoryRepository.findById(TIRES_AND_WHEELS_ID)).thenReturn(Optional.of(tiresAndWheels));
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

    private ProductUpdateRequestDto updateRequest() {
        ProductUpdateRequestDto request = new ProductUpdateRequestDto();
        request.setName("Group 31 AGM Battery");
        request.setDescription("Heavy duty AGM battery");
        request.setUnitOfMeasure("EA");
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

    // ─── #1536: category / subcategory pair invariant ─────────────────────────

    @Test
    void createProduct_subcategoryUnderDifferentCategory_throwsBusinessRuleViolation() {
        ProductCreateRequestDto request = createRequest();
        // Batteries is a child of Electrical System, not of Tires & Wheels. Both ids resolve
        // individually; what fails is the cross-entity invariant, so this is a 409, not a 400.
        request.setCategoryId(TIRES_AND_WHEELS_ID);
        request.setSubcategoryId(BATTERIES_ID);

        assertThatThrownBy(() -> service.createProduct(request)).isInstanceOf(CatalogBusinessRuleException.class);

        org.mockito.Mockito.verify(productRepository, org.mockito.Mockito.never())
                .save(any(ProductEntity.class));
        org.mockito.Mockito.verify(catalogFactPublisher, org.mockito.Mockito.never())
                .publishProductUpdated(any());
    }

    @Test
    void createProduct_subcategoryUnderDifferentCategory_messageNamesBothCategories() {
        ProductCreateRequestDto request = createRequest();
        request.setCategoryId(TIRES_AND_WHEELS_ID);
        request.setSubcategoryId(BATTERIES_ID);

        assertThatThrownBy(() -> service.createProduct(request))
                .isInstanceOf(CatalogBusinessRuleException.class)
                .hasMessageContaining("Batteries")
                .hasMessageContaining("Electrical System")
                .hasMessageContaining(ELECTRICAL_SYSTEM_ID.toString())
                .hasMessageContaining("Tires & Wheels")
                .hasMessageContaining(TIRES_AND_WHEELS_ID.toString());
    }

    @Test
    void createProduct_subcategoryWithoutCategoryId_derivesParentCategory() {
        ProductCreateRequestDto request = createRequest();
        request.setSubcategoryId(BATTERIES_ID);

        var dto = service.createProduct(request);

        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        org.mockito.Mockito.verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isSameAs(electricalSystem);
        assertThat(dto.getCategory()).isNotNull();
        assertThat(dto.getCategory().getId()).isEqualTo(ELECTRICAL_SYSTEM_ID);
        assertThat(dto.getSubcategory().getId()).isEqualTo(BATTERIES_ID);
    }

    @Test
    void createProduct_matchingPair_persistsBoth() {
        ProductCreateRequestDto request = createRequest();
        request.setCategoryId(ELECTRICAL_SYSTEM_ID);
        request.setSubcategoryId(BATTERIES_ID);

        var dto = service.createProduct(request);

        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        org.mockito.Mockito.verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isSameAs(electricalSystem);
        assertThat(captor.getValue().getSubcategory()).isSameAs(batteries);
        assertThat(dto.getCategory().getId()).isEqualTo(ELECTRICAL_SYSTEM_ID);
        assertThat(dto.getSubcategory().getId()).isEqualTo(BATTERIES_ID);
    }

    @Test
    void createProduct_categoryOnlyWithoutSubcategory_isAllowed() {
        ProductCreateRequestDto request = createRequest();
        request.setCategoryId(TIRES_AND_WHEELS_ID);

        var dto = service.createProduct(request);

        assertThat(dto.getCategory()).isNotNull();
        assertThat(dto.getCategory().getId()).isEqualTo(TIRES_AND_WHEELS_ID);
        assertThat(dto.getSubcategory()).isNull();
    }

    @Test
    void createProduct_neitherCategoryNorSubcategory_isAllowed() {
        var dto = service.createProduct(createRequest());

        assertThat(dto.getCategory()).isNull();
        assertThat(dto.getSubcategory()).isNull();
    }

    @Test
    void updateProduct_subcategoryUnderDifferentCategory_throwsBusinessRuleViolation() {
        ProductEntity existing = new ProductEntity();
        existing.setId(PRODUCT_ID);
        existing.setSku("MOT-BAT-31");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));

        ProductUpdateRequestDto request = updateRequest();
        request.setCategoryId(TIRES_AND_WHEELS_ID);
        request.setSubcategoryId(BATTERIES_ID);

        assertThatThrownBy(() -> service.updateProduct(PRODUCT_ID, request))
                .isInstanceOf(CatalogBusinessRuleException.class)
                .hasMessageContaining("Batteries")
                .hasMessageContaining("Electrical System");

        org.mockito.Mockito.verify(productRepository, org.mockito.Mockito.never())
                .save(any(ProductEntity.class));
    }

    @Test
    void updateProduct_subcategoryWithoutCategoryId_derivesParentCategoryRatherThanClearingIt() {
        // Deliberate, narrow exception to the clear-on-omit convention (#1536): the category is now a
        // function of the subcategory, so omitting it while supplying a subcategory cannot mean "clear"
        // without leaving the row in a state the schema says is impossible.
        ProductEntity existing = new ProductEntity();
        existing.setId(PRODUCT_ID);
        existing.setSku("MOT-BAT-31");
        existing.setCategory(tiresAndWheels);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));

        ProductUpdateRequestDto request = updateRequest();
        request.setSubcategoryId(BATTERIES_ID);

        var dto = service.updateProduct(PRODUCT_ID, request);

        assertThat(existing.getCategory()).isSameAs(electricalSystem);
        assertThat(dto.getCategory()).isNotNull();
        assertThat(dto.getCategory().getId()).isEqualTo(ELECTRICAL_SYSTEM_ID);
        assertThat(dto.getSubcategory().getId()).isEqualTo(BATTERIES_ID);
    }
}
