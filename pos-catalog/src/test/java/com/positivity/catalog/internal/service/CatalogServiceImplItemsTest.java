package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.config.CatalogFactPublisher;
import com.positivity.catalog.internal.dto.CatalogDto;
import com.positivity.catalog.internal.dto.CatalogItemRequestDto;
import com.positivity.catalog.internal.dto.CatalogItemResponseDto;
import com.positivity.catalog.internal.dto.NonInventoryProductDto;
import com.positivity.catalog.internal.dto.ProductDto;
import com.positivity.catalog.internal.dto.ServiceDto;
import com.positivity.catalog.internal.entity.CatalogEntity;
import com.positivity.catalog.internal.entity.Category;
import com.positivity.catalog.internal.entity.DimensionEntity;
import com.positivity.catalog.internal.entity.NonInventoryProductEntity;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.entity.Subcategory;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.CatalogRepository;
import com.positivity.catalog.internal.repository.NonInventoryProductRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.ServiceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Catalog item and catalog CRUD. Service search is covered by {@link CatalogServiceImplTest};
 * this class covers the type-dispatching add/update/delete paths and the entity-to-DTO mapping.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CatalogServiceImpl item and catalog CRUD")
class CatalogServiceImplItemsTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9a01");
    private static final UUID SERVICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9a02");
    private static final UUID NON_INVENTORY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9a03");
    private static final UUID CATALOG_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9a04");
    private static final UUID MANUFACTURER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9a05");
    private static final Instant CREATED_AT = Instant.parse("2026-01-04T08:00:00Z");

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private NonInventoryProductRepository nonInventoryProductRepository;

    @Mock
    private CatalogRepository catalogRepository;

    @Mock
    private CatalogFactPublisher catalogFactPublisher;

    private CatalogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CatalogServiceImpl(
                productRepository,
                serviceRepository,
                nonInventoryProductRepository,
                catalogRepository,
                catalogFactPublisher);
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(serviceRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(nonInventoryProductRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(catalogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static CatalogItemRequestDto itemRequest() {
        CatalogItemRequestDto request = new CatalogItemRequestDto();
        request.setName("Tire 205/55R16");
        request.setShortDescription("All-season tire");
        request.setLongDescription("All-season touring tire, 91V");
        request.setImages(List.of("https://example.invalid/tire.png"));
        request.setManufacturerPartNumber("MPN-1");
        request.setManufacturerId(MANUFACTURER_ID.toString());
        request.setManufacturerName("Acme");
        request.setManufacturerWarranty("60 months");
        request.setManufacturerBrand("AcmeGrip");
        request.setCountryOfOrigin("US");
        request.setSku("SKU-1");
        request.setProductCode("3286340123456");
        request.setType("TIRE");
        request.setMaterial("Rubber");
        request.setColor("Black");
        request.setWarranty("24 months");
        request.setSpecifications("{\"loadIndex\":91}");
        return request;
    }

    private static ProductEntity productEntity() {
        ProductEntity product = new ProductEntity();
        product.setId(PRODUCT_ID);
        product.setName("Tire 205/55R16");
        product.setShortDescription("All-season tire");
        product.setLongDescription("All-season touring tire, 91V");
        product.setSku("SKU-1");
        product.setManufacturerPartNumber("MPN-1");
        product.setProductCode("3286340123456");
        product.setUpc("3286340123456");
        return product;
    }

    private static ServiceEntity serviceEntity() {
        ServiceEntity entity = new ServiceEntity();
        entity.setId(SERVICE_ID);
        entity.setName("Mount and balance");
        entity.setShortDescription("Per wheel");
        return entity;
    }

    private static NonInventoryProductEntity nonInventoryEntity() {
        NonInventoryProductEntity entity = new NonInventoryProductEntity();
        entity.setId(NON_INVENTORY_ID);
        entity.setName("Shop supplies");
        return entity;
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        void mapsAProductWithItsCategorySubcategoryAndDimensions() {
            ProductEntity entity = productEntity();
            Category category = new Category();
            category.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9b01"));
            category.setName("Tires");
            entity.setCategory(category);
            Subcategory subcategory = new Subcategory();
            subcategory.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9b02"));
            subcategory.setName("Passenger");
            // #1536: a subcategory always has a parent category; keep the fixture faithful to the schema.
            subcategory.setCategory(category);
            entity.setSubcategory(subcategory);
            DimensionEntity dimension = new DimensionEntity();
            dimension.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9b03"));
            dimension.setDescription("Section width");
            dimension.setUnitOfMeasure("mm");
            dimension.setDimensionValue(205d);
            entity.setDimensions(List.of(dimension));
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(entity));

            ProductDto dto = service.getProductById(PRODUCT_ID).orElseThrow();

            assertThat(dto.getId()).isEqualTo(PRODUCT_ID);
            assertThat(dto.getCategory().getName()).isEqualTo("Tires");
            assertThat(dto.getSubcategory().getName()).isEqualTo("Passenger");
            assertThat(dto.getDimensions()).hasSize(1);
            assertThat(dto.getDimensions().get(0).getUnitOfMeasure()).isEqualTo("mm");
            // The MPN is echoed into the dedicated mpn field as well as its own.
            assertThat(dto.getMpn()).isEqualTo("MPN-1");
        }

        @Test
        void leavesCategorySubcategoryAndDimensionsUnsetWhenTheProductHasNone() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(productEntity()));

            ProductDto dto = service.getProductById(PRODUCT_ID).orElseThrow();

            assertThat(dto.getCategory()).isNull();
            assertThat(dto.getSubcategory()).isNull();
            assertThat(dto.getDimensions()).isEmpty();
        }

        @Test
        void returnsEmptyForAnUnknownProduct() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

            assertThat(service.getProductById(PRODUCT_ID)).isEmpty();
        }

        @Test
        void listsProductsByExactName() {
            when(productRepository.findByName("Tire 205/55R16")).thenReturn(List.of(productEntity()));

            assertThat(service.getProductsByName("Tire 205/55R16")).hasSize(1);
        }

        @Test
        void readsAServiceById() {
            when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.of(serviceEntity()));

            ServiceDto dto = service.getServiceById(SERVICE_ID).orElseThrow();

            assertThat(dto.getName()).isEqualTo("Mount and balance");
            assertThat(dto.getShortDescription()).isEqualTo("Per wheel");
        }

        @Test
        void listsServicesByExactName() {
            when(serviceRepository.findByName("Mount and balance")).thenReturn(List.of(serviceEntity()));

            assertThat(service.getServicesByName("Mount and balance")).hasSize(1);
        }

        @Test
        void readsANonInventoryProductById() {
            when(nonInventoryProductRepository.findById(NON_INVENTORY_ID))
                    .thenReturn(Optional.of(nonInventoryEntity()));

            NonInventoryProductDto dto =
                    service.getNonInventoryProductById(NON_INVENTORY_ID).orElseThrow();

            assertThat(dto.getName()).isEqualTo("Shop supplies");
        }

        @Test
        void listsNonInventoryProductsByExactName() {
            when(nonInventoryProductRepository.findByName("Shop supplies")).thenReturn(List.of(nonInventoryEntity()));

            assertThat(service.getNonInventoryProductsByName("Shop supplies")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("addCatalogItem")
    class AddCatalogItem {

        @Test
        void createsAProductAndCopiesTheRequestOntoTheEntity() {
            CatalogItemResponseDto response = service.addCatalogItem("PRODUCT", itemRequest());

            ArgumentCaptor<ProductEntity> saved = ArgumentCaptor.forClass(ProductEntity.class);
            verify(productRepository).save(saved.capture());
            assertThat(saved.getValue().getName()).isEqualTo("Tire 205/55R16");
            assertThat(saved.getValue().getManufacturerId()).isEqualTo(MANUFACTURER_ID);
            // The product code doubles as the UPC, and the long description as the description.
            assertThat(saved.getValue().getUpc()).isEqualTo("3286340123456");
            assertThat(saved.getValue().getDescription()).isEqualTo("All-season touring tire, 91V");
            assertThat(saved.getValue().getAttributes()).isEqualTo("{\"loadIndex\":91}");
            assertThat(response.getItemType()).isEqualTo("product");
        }

        @Test
        void leavesTheManufacturerIdUnsetWhenTheRequestHasNone() {
            CatalogItemRequestDto request = itemRequest();
            request.setManufacturerId("  ");

            service.addCatalogItem("product", request);

            ArgumentCaptor<ProductEntity> saved = ArgumentCaptor.forClass(ProductEntity.class);
            verify(productRepository).save(saved.capture());
            assertThat(saved.getValue().getManufacturerId()).isNull();
        }

        @Test
        void createsAService() {
            CatalogItemResponseDto response = service.addCatalogItem("service", itemRequest());

            assertThat(response.getItemType()).isEqualTo("service");
            verify(serviceRepository).saveAndFlush(any(ServiceEntity.class));
        }

        @Test
        @DisplayName("announces the new service so consumers can resolve service: references (#1306)")
        void publishesTheServiceFact() {
            service.addCatalogItem("service", itemRequest());

            ArgumentCaptor<ServiceEntity> published = ArgumentCaptor.forClass(ServiceEntity.class);
            verify(catalogFactPublisher).publishServiceUpdated(published.capture());
            assertThat(published.getValue().getName()).isEqualTo("Tire 205/55R16");
        }

        @Test
        void createsANonInventoryItem() {
            CatalogItemResponseDto response = service.addCatalogItem("noninventory", itemRequest());

            assertThat(response.getItemType()).isEqualTo("noninventory");
            verify(nonInventoryProductRepository).save(any(NonInventoryProductEntity.class));
        }

        @Test
        void rejectsAnUnknownItemType() {
            CatalogItemRequestDto request = itemRequest();

            assertThatThrownBy(() -> service.addCatalogItem("widget", request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("Unsupported item type: widget");
        }

        @Test
        void rejectsAMissingItemType() {
            CatalogItemRequestDto request = itemRequest();

            assertThatThrownBy(() -> service.addCatalogItem(null, request))
                    .isInstanceOf(CatalogValidationException.class);
        }
    }

    @Nested
    @DisplayName("updateCatalogItem")
    class UpdateCatalogItem {

        @Test
        void keepsTheExistingProductIdOnTheReplacementEntity() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(productEntity()));

            Optional<CatalogItemResponseDto> response = service.updateCatalogItem("product", PRODUCT_ID, itemRequest());

            assertThat(response).isPresent();
            ArgumentCaptor<ProductEntity> saved = ArgumentCaptor.forClass(ProductEntity.class);
            verify(productRepository).save(saved.capture());
            assertThat(saved.getValue().getId()).isEqualTo(PRODUCT_ID);
        }

        @Test
        void returnsEmptyForAnUnknownProduct() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

            assertThat(service.updateCatalogItem("product", PRODUCT_ID, itemRequest()))
                    .isEmpty();
            verify(productRepository, never()).save(any());
        }

        @Test
        void updatesAService() {
            when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.of(serviceEntity()));

            assertThat(service.updateCatalogItem("service", SERVICE_ID, itemRequest()))
                    .isPresent();
            verify(catalogFactPublisher).publishServiceUpdated(any(ServiceEntity.class));
        }

        @Test
        @DisplayName("carries the original createdAt onto the replacement entity, so the fact keeps it")
        void keepsCreatedAtOnTheReplacementService() {
            ServiceEntity existing = serviceEntity();
            existing.setCreatedAt(CREATED_AT);
            when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.of(existing));

            service.updateCatalogItem("service", SERVICE_ID, itemRequest());

            ArgumentCaptor<ServiceEntity> saved = ArgumentCaptor.forClass(ServiceEntity.class);
            verify(serviceRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getId()).isEqualTo(SERVICE_ID);
            assertThat(saved.getValue().getCreatedAt()).isEqualTo(CREATED_AT);
        }

        @Test
        void returnsEmptyForAnUnknownService() {
            when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.empty());

            assertThat(service.updateCatalogItem("service", SERVICE_ID, itemRequest()))
                    .isEmpty();
            verifyNoInteractions(catalogFactPublisher);
        }

        @Test
        void updatesANonInventoryItem() {
            when(nonInventoryProductRepository.findById(NON_INVENTORY_ID))
                    .thenReturn(Optional.of(nonInventoryEntity()));

            assertThat(service.updateCatalogItem("noninventory", NON_INVENTORY_ID, itemRequest()))
                    .isPresent();
        }

        @Test
        void returnsEmptyForAnUnknownNonInventoryItem() {
            when(nonInventoryProductRepository.findById(NON_INVENTORY_ID)).thenReturn(Optional.empty());

            assertThat(service.updateCatalogItem("noninventory", NON_INVENTORY_ID, itemRequest()))
                    .isEmpty();
        }

        @Test
        void rejectsAnUnknownItemType() {
            CatalogItemRequestDto request = itemRequest();

            assertThatThrownBy(() -> service.updateCatalogItem("widget", PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class);
        }
    }

    @Nested
    @DisplayName("deleteCatalogItem")
    class DeleteCatalogItem {

        @Test
        @DisplayName("deletes the product and announces the removal, so replicas stop resolving it")
        void deletesAnExistingProduct() {
            ProductEntity existing = productEntity();
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));

            assertThat(service.deleteCatalogItem("product", PRODUCT_ID)).isTrue();
            verify(productRepository).delete(existing);
            verify(catalogFactPublisher).publishProductRemoved(existing);
        }

        @Test
        @DisplayName("announces the removal before the row goes, while its attribute rows still exist")
        void publishesTheProductTombstoneBeforeDeleting() {
            ProductEntity existing = productEntity();
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));

            service.deleteCatalogItem("product", PRODUCT_ID);

            // The payload is assembled from the product's UoM and substitution rows, which the
            // delete takes with it.
            InOrder order = inOrder(catalogFactPublisher, productRepository);
            order.verify(catalogFactPublisher).publishProductRemoved(existing);
            order.verify(productRepository).delete(existing);
        }

        @Test
        void reportsFalseForAProductThatIsNotThere() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

            assertThat(service.deleteCatalogItem("product", PRODUCT_ID)).isFalse();
            verify(productRepository, never()).delete(any(ProductEntity.class));
            verifyNoInteractions(catalogFactPublisher);
        }

        @Test
        @DisplayName("deletes the service and announces the removal, so replicas stop resolving it")
        void deletesAnExistingService() {
            ServiceEntity existing = serviceEntity();
            when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.of(existing));

            assertThat(service.deleteCatalogItem("service", SERVICE_ID)).isTrue();
            verify(serviceRepository).delete(existing);
            verify(catalogFactPublisher).publishServiceRemoved(existing);
        }

        @Test
        void reportsFalseForAServiceThatIsNotThere() {
            when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.empty());

            assertThat(service.deleteCatalogItem("service", SERVICE_ID)).isFalse();
            verifyNoInteractions(catalogFactPublisher);
        }

        @Test
        void deletesAnExistingNonInventoryItem() {
            when(nonInventoryProductRepository.existsById(NON_INVENTORY_ID)).thenReturn(true);

            assertThat(service.deleteCatalogItem("noninventory", NON_INVENTORY_ID))
                    .isTrue();
            verify(nonInventoryProductRepository).deleteById(NON_INVENTORY_ID);
        }

        @Test
        void reportsFalseForANonInventoryItemThatIsNotThere() {
            when(nonInventoryProductRepository.existsById(NON_INVENTORY_ID)).thenReturn(false);

            assertThat(service.deleteCatalogItem("noninventory", NON_INVENTORY_ID))
                    .isFalse();
        }

        @Test
        void rejectsAnUnknownItemType() {
            assertThatThrownBy(() -> service.deleteCatalogItem("widget", PRODUCT_ID))
                    .isInstanceOf(CatalogValidationException.class);
        }
    }

    @Nested
    @DisplayName("catalogs")
    class Catalogs {

        private CatalogDto catalogRequest() {
            CatalogDto request = new CatalogDto();
            request.setName("Spring promo");
            request.setDescription("Seasonal bundle");
            request.setProductIds(List.of(PRODUCT_ID));
            request.setServiceIds(List.of(SERVICE_ID));
            request.setNonInventoryProductIds(List.of(NON_INVENTORY_ID));
            return request;
        }

        @Test
        void resolvesEveryMemberIdWhenCreatingACatalog() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(productEntity()));
            when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.of(serviceEntity()));
            when(nonInventoryProductRepository.findById(NON_INVENTORY_ID))
                    .thenReturn(Optional.of(nonInventoryEntity()));

            CatalogDto response = service.addCatalog(catalogRequest());

            assertThat(response.getProductIds()).containsExactly(PRODUCT_ID);
            assertThat(response.getServiceIds()).containsExactly(SERVICE_ID);
            assertThat(response.getNonInventoryProductIds()).containsExactly(NON_INVENTORY_ID);
        }

        @Test
        void dropsMemberIdsThatNoLongerResolve() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());
            when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.empty());
            when(nonInventoryProductRepository.findById(NON_INVENTORY_ID)).thenReturn(Optional.empty());

            CatalogDto response = service.addCatalog(catalogRequest());

            assertThat(response.getProductIds()).isEmpty();
            assertThat(response.getServiceIds()).isEmpty();
            assertThat(response.getNonInventoryProductIds()).isEmpty();
        }

        @Test
        void acceptsACatalogWithNoMembersAtAll() {
            CatalogDto request = new CatalogDto();
            request.setName("Empty");

            CatalogDto response = service.addCatalog(request);

            assertThat(response.getProductIds()).isEmpty();
            assertThat(response.getServiceIds()).isEmpty();
            assertThat(response.getNonInventoryProductIds()).isEmpty();
        }

        @Test
        void readsACatalogById() {
            CatalogEntity entity = new CatalogEntity();
            entity.setId(CATALOG_ID);
            entity.setName("Spring promo");
            entity.setProducts(List.of(productEntity()));
            when(catalogRepository.findById(CATALOG_ID)).thenReturn(Optional.of(entity));

            CatalogDto dto = service.getCatalogById(CATALOG_ID).orElseThrow();

            assertThat(dto.getProductIds()).containsExactly(PRODUCT_ID);
            assertThat(dto.getServiceIds()).isEmpty();
        }

        @Test
        void searchesCatalogsByPartialName() {
            CatalogEntity entity = new CatalogEntity();
            entity.setId(CATALOG_ID);
            entity.setName("Spring promo");
            when(catalogRepository.findByNameContainingIgnoreCase("spring")).thenReturn(List.of(entity));

            assertThat(service.getCatalogsByName("spring")).hasSize(1);
        }

        @Test
        void keepsTheExistingCatalogIdOnUpdate() {
            CatalogEntity existing = new CatalogEntity();
            existing.setId(CATALOG_ID);
            when(catalogRepository.findById(CATALOG_ID)).thenReturn(Optional.of(existing));
            CatalogDto request = new CatalogDto();
            request.setName("Renamed");

            CatalogDto response = service.updateCatalog(CATALOG_ID, request).orElseThrow();

            assertThat(response.getId()).isEqualTo(CATALOG_ID);
            assertThat(response.getName()).isEqualTo("Renamed");
        }

        @Test
        void returnsEmptyWhenUpdatingAnUnknownCatalog() {
            when(catalogRepository.findById(CATALOG_ID)).thenReturn(Optional.empty());

            assertThat(service.updateCatalog(CATALOG_ID, new CatalogDto())).isEmpty();
        }

        @Test
        void deletesAnExistingCatalog() {
            when(catalogRepository.existsById(CATALOG_ID)).thenReturn(true);

            assertThat(service.deleteCatalog(CATALOG_ID)).isTrue();
            verify(catalogRepository).deleteById(CATALOG_ID);
        }

        @Test
        void reportsFalseForACatalogThatIsNotThere() {
            when(catalogRepository.existsById(CATALOG_ID)).thenReturn(false);

            assertThat(service.deleteCatalog(CATALOG_ID)).isFalse();
            verify(catalogRepository, never()).deleteById(any(UUID.class));
        }
    }
}
