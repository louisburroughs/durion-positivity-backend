package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.CatalogDto;
import com.positivity.catalog.internal.dto.CatalogItemRequestDto;
import com.positivity.catalog.internal.dto.CatalogItemResponseDto;
import com.positivity.catalog.internal.dto.CategoryDto;
import com.positivity.catalog.internal.dto.DimensionDto;
import com.positivity.catalog.internal.dto.NonInventoryProductDto;
import com.positivity.catalog.internal.dto.ProductDto;
import com.positivity.catalog.internal.dto.ServiceDto;
import com.positivity.catalog.internal.dto.SubcategoryDto;
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
import com.positivity.catalog.service.CatalogService;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CatalogServiceImpl implements CatalogService {

    private static final String PRODUCT = "product";
    private static final String SERVICE = "service";
    private static final String NONINVENTORY = "noninventory";

    private final ProductRepository productRepository;
    private final ServiceRepository serviceRepository;
    private final NonInventoryProductRepository nonInventoryProductRepository;
    private final CatalogRepository catalogRepository;

    public CatalogServiceImpl(
            ProductRepository productRepository,
            ServiceRepository serviceRepository,
            NonInventoryProductRepository nonInventoryProductRepository,
            CatalogRepository catalogRepository) {
        this.productRepository = productRepository;
        this.serviceRepository = serviceRepository;
        this.nonInventoryProductRepository = nonInventoryProductRepository;
        this.catalogRepository = catalogRepository;
    }

    public Optional<ProductDto> getProductById(UUID productId) {
        return productRepository.findById(productId).map(this::toProductDto);
    }

    public List<ProductDto> getProductsByName(String name) {
        return productRepository.findByName(name).stream()
                .map(this::toProductDto)
                .toList();
    }

    public Optional<ServiceDto> getServiceById(UUID serviceId) {
        return serviceRepository.findById(serviceId).map(this::toServiceDto);
    }

    public List<ServiceDto> getServicesByName(String name) {
        return serviceRepository.findByName(name).stream()
                .map(this::toServiceDto)
                .toList();
    }

    public List<ServiceDto> searchServices(String q, int limit) {
        if (q == null || q.isBlank()) {
            return Collections.emptyList();
        }
        int capped = Math.max(1, Math.min(limit, 100));
        return serviceRepository.findByNameContainingIgnoreCaseOrderByNameAsc(q.trim()).stream()
                .map(this::toServiceDto)
                .limit(capped)
                .toList();
    }

    public Optional<NonInventoryProductDto> getNonInventoryProductById(UUID productId) {
        return nonInventoryProductRepository.findById(productId).map(this::toNonInventoryProductDto);
    }

    public List<NonInventoryProductDto> getNonInventoryProductsByName(String name) {
        return nonInventoryProductRepository.findByName(name).stream()
                .map(this::toNonInventoryProductDto)
                .toList();
    }

    public Optional<CatalogDto> getCatalogById(UUID catalogId) {
        return catalogRepository.findById(catalogId).map(this::toCatalogDto);
    }

    public List<CatalogDto> getCatalogsByName(String name) {
        return catalogRepository.findByNameContainingIgnoreCase(name).stream()
                .map(this::toCatalogDto)
                .toList();
    }

    public CatalogItemResponseDto addCatalogItem(String type, CatalogItemRequestDto request) {
        return switch (normalizeType(type)) {
            case PRODUCT -> toCatalogItemResponse(PRODUCT, productRepository.save(toProductEntity(request)));
            case SERVICE -> toCatalogItemResponse(SERVICE, serviceRepository.save(toServiceEntity(request)));
            case NONINVENTORY ->
                toCatalogItemResponse(NONINVENTORY, nonInventoryProductRepository.save(toNonInventoryEntity(request)));
            default -> throw unsupportedType(type);
        };
    }

    public Optional<CatalogItemResponseDto> updateCatalogItem(
            String type, UUID catalogId, CatalogItemRequestDto request) {
        return switch (normalizeType(type)) {
            case PRODUCT ->
                productRepository.findById(catalogId).map(existing -> {
                    ProductEntity updated = toProductEntity(request);
                    updated.setId(catalogId);
                    return toCatalogItemResponse(PRODUCT, productRepository.save(updated));
                });
            case SERVICE ->
                serviceRepository.findById(catalogId).map(existing -> {
                    ServiceEntity updated = toServiceEntity(request);
                    updated.setId(catalogId);
                    return toCatalogItemResponse(SERVICE, serviceRepository.save(updated));
                });
            case NONINVENTORY ->
                nonInventoryProductRepository.findById(catalogId).map(existing -> {
                    NonInventoryProductEntity updated = toNonInventoryEntity(request);
                    updated.setId(catalogId);
                    return toCatalogItemResponse(NONINVENTORY, nonInventoryProductRepository.save(updated));
                });
            default -> throw unsupportedType(type);
        };
    }

    public boolean deleteCatalogItem(String type, UUID catalogId) {
        return switch (normalizeType(type)) {
            case PRODUCT -> deleteProduct(catalogId);
            case SERVICE -> deleteService(catalogId);
            case NONINVENTORY -> deleteNonInventoryProduct(catalogId);
            default -> throw unsupportedType(type);
        };
    }

    public CatalogDto addCatalog(CatalogDto request) {
        return toCatalogDto(catalogRepository.save(toCatalogEntity(request)));
    }

    public Optional<CatalogDto> updateCatalog(UUID catalogId, CatalogDto request) {
        return catalogRepository.findById(catalogId).map(existing -> {
            CatalogEntity updated = toCatalogEntity(request);
            updated.setId(catalogId);
            return toCatalogDto(catalogRepository.save(updated));
        });
    }

    public boolean deleteCatalog(UUID catalogId) {
        if (!catalogRepository.existsById(catalogId)) {
            return false;
        }
        catalogRepository.deleteById(catalogId);
        return true;
    }

    private String normalizeType(String type) {
        return type == null ? "" : type.toLowerCase();
    }

    private CatalogValidationException unsupportedType(String type) {
        return new CatalogValidationException("Unsupported item type: " + type);
    }

    private ProductEntity toProductEntity(CatalogItemRequestDto dto) {
        ProductEntity product = new ProductEntity();
        product.setName(dto.getName());
        product.setShortDescription(dto.getShortDescription());
        product.setLongDescription(dto.getLongDescription());
        product.setImages(dto.getImages());
        product.setManufacturerPartNumber(dto.getManufacturerPartNumber());
        product.setManufacturerId(parseUuid(dto.getManufacturerId()));
        product.setManufacturerName(dto.getManufacturerName());
        product.setManufacturerWarranty(dto.getManufacturerWarranty());
        product.setManufacturerBrand(dto.getManufacturerBrand());
        product.setCountryOfOrigin(dto.getCountryOfOrigin());
        product.setSku(dto.getSku());
        product.setProductCode(dto.getProductCode());
        product.setUpc(dto.getProductCode());
        product.setType(dto.getType());
        product.setMaterial(dto.getMaterial());
        product.setColor(dto.getColor());
        product.setWarranty(dto.getWarranty());
        product.setSpecifications(dto.getSpecifications());
        product.setAttributes(dto.getSpecifications());
        product.setDescription(dto.getLongDescription());
        return product;
    }

    private ServiceEntity toServiceEntity(CatalogItemRequestDto dto) {
        ServiceEntity service = new ServiceEntity();
        service.setName(dto.getName());
        service.setShortDescription(dto.getShortDescription());
        service.setLongDescription(dto.getLongDescription());
        return service;
    }

    private NonInventoryProductEntity toNonInventoryEntity(CatalogItemRequestDto dto) {
        NonInventoryProductEntity item = new NonInventoryProductEntity();
        item.setName(dto.getName());
        item.setShortDescription(dto.getShortDescription());
        item.setLongDescription(dto.getLongDescription());
        return item;
    }

    private CatalogEntity toCatalogEntity(CatalogDto dto) {
        CatalogEntity catalog = new CatalogEntity();
        catalog.setName(dto.getName());
        catalog.setDescription(dto.getDescription());
        catalog.setProducts(resolveProducts(dto.getProductIds()));
        catalog.setServices(resolveServices(dto.getServiceIds()));
        catalog.setNonInventoryProducts(resolveNonInventoryProducts(dto.getNonInventoryProductIds()));
        return catalog;
    }

    private List<ProductEntity> resolveProducts(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Collections.emptyList();
        }
        return productIds.stream()
                .map(productRepository::findById)
                .flatMap(Optional::stream)
                .toList();
    }

    private List<ServiceEntity> resolveServices(List<UUID> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Collections.emptyList();
        }
        return serviceIds.stream()
                .map(serviceRepository::findById)
                .flatMap(Optional::stream)
                .toList();
    }

    private List<NonInventoryProductEntity> resolveNonInventoryProducts(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Collections.emptyList();
        }
        return productIds.stream()
                .map(nonInventoryProductRepository::findById)
                .flatMap(Optional::stream)
                .toList();
    }

    private boolean deleteProduct(UUID productId) {
        if (!productRepository.existsById(productId)) {
            return false;
        }
        productRepository.deleteById(productId);
        return true;
    }

    private boolean deleteService(UUID serviceId) {
        if (!serviceRepository.existsById(serviceId)) {
            return false;
        }
        serviceRepository.deleteById(serviceId);
        return true;
    }

    private boolean deleteNonInventoryProduct(UUID productId) {
        if (!nonInventoryProductRepository.existsById(productId)) {
            return false;
        }
        nonInventoryProductRepository.deleteById(productId);
        return true;
    }

    private CatalogItemResponseDto toCatalogItemResponse(String type, ProductEntity entity) {
        CatalogItemResponseDto dto = new CatalogItemResponseDto();
        dto.setItemType(type);
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setShortDescription(entity.getShortDescription());
        dto.setLongDescription(entity.getLongDescription());
        return dto;
    }

    private CatalogItemResponseDto toCatalogItemResponse(String type, ServiceEntity entity) {
        CatalogItemResponseDto dto = new CatalogItemResponseDto();
        dto.setItemType(type);
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setShortDescription(entity.getShortDescription());
        dto.setLongDescription(entity.getLongDescription());
        return dto;
    }

    private CatalogItemResponseDto toCatalogItemResponse(String type, NonInventoryProductEntity entity) {
        CatalogItemResponseDto dto = new CatalogItemResponseDto();
        dto.setItemType(type);
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setShortDescription(entity.getShortDescription());
        dto.setLongDescription(entity.getLongDescription());
        return dto;
    }

    private ProductDto toProductDto(ProductEntity entity) {
        ProductDto dto = new ProductDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setShortDescription(entity.getShortDescription());
        dto.setLongDescription(entity.getLongDescription());
        dto.setImages(entity.getImages());
        dto.setManufacturerPartNumber(entity.getManufacturerPartNumber());
        dto.setManufacturerId(entity.getManufacturerId());
        dto.setManufacturerName(entity.getManufacturerName());
        dto.setManufacturerWarranty(entity.getManufacturerWarranty());
        dto.setManufacturerBrand(entity.getManufacturerBrand());
        dto.setCountryOfOrigin(entity.getCountryOfOrigin());
        dto.setSku(entity.getSku());
        dto.setMpn(entity.getManufacturerPartNumber());
        dto.setProductCode(entity.getProductCode());
        dto.setUpc(entity.getUpc());
        dto.setAttributes(entity.getAttributes());
        dto.setProductCodeType(entity.getProductCodeType());
        dto.setCategory(toCategoryDto(entity.getCategory()));
        dto.setSubcategory(toSubcategoryDto(entity.getSubcategory()));
        dto.setType(entity.getType());
        dto.setDimensions(toDimensionDtos(entity.getDimensions()));
        dto.setMaterial(entity.getMaterial());
        dto.setColor(entity.getColor());
        dto.setWarranty(entity.getWarranty());
        dto.setSpecifications(entity.getSpecifications());
        dto.setDescription(entity.getDescription());
        dto.setUnitOfMeasure(entity.getUnitOfMeasure());
        dto.setStatus(entity.getStatus());
        dto.setLifecycleState(entity.getLifecycleState());
        dto.setLifecycleStateEffectiveAt(entity.getLifecycleStateEffectiveAt());
        dto.setLastStateChangedBy(entity.getLastStateChangedBy());
        dto.setLastStateChangedAt(entity.getLastStateChangedAt());
        dto.setLifecycleOverrideReason(entity.getLifecycleOverrideReason());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return UUID.fromString(value);
    }

    private ServiceDto toServiceDto(ServiceEntity entity) {
        ServiceDto dto = new ServiceDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setShortDescription(entity.getShortDescription());
        dto.setLongDescription(entity.getLongDescription());
        return dto;
    }

    private NonInventoryProductDto toNonInventoryProductDto(NonInventoryProductEntity entity) {
        NonInventoryProductDto dto = new NonInventoryProductDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setShortDescription(entity.getShortDescription());
        dto.setLongDescription(entity.getLongDescription());
        return dto;
    }

    private CatalogDto toCatalogDto(CatalogEntity entity) {
        CatalogDto dto = new CatalogDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setProductIds(
                entity.getProducts() == null
                        ? Collections.emptyList()
                        : entity.getProducts().stream()
                                .map(ProductEntity::getId)
                                .toList());
        dto.setServiceIds(
                entity.getServices() == null
                        ? Collections.emptyList()
                        : entity.getServices().stream()
                                .map(ServiceEntity::getId)
                                .toList());
        dto.setNonInventoryProductIds(
                entity.getNonInventoryProducts() == null
                        ? Collections.emptyList()
                        : entity.getNonInventoryProducts().stream()
                                .map(NonInventoryProductEntity::getId)
                                .toList());
        return dto;
    }

    private CategoryDto toCategoryDto(Category category) {
        if (category == null) {
            return null;
        }
        CategoryDto dto = new CategoryDto();
        dto.setId(category.getId());
        dto.setName(category.getName());
        return dto;
    }

    private SubcategoryDto toSubcategoryDto(Subcategory subcategory) {
        if (subcategory == null) {
            return null;
        }
        SubcategoryDto dto = new SubcategoryDto();
        dto.setId(subcategory.getId());
        dto.setName(subcategory.getName());
        return dto;
    }

    private List<DimensionDto> toDimensionDtos(List<DimensionEntity> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return Collections.emptyList();
        }
        return dimensions.stream().map(this::toDimensionDto).toList();
    }

    private DimensionDto toDimensionDto(DimensionEntity entity) {
        DimensionDto dto = new DimensionDto();
        dto.setId(entity.getId());
        dto.setDimensionType(entity.getDimensionType());
        dto.setDescription(entity.getDescription());
        dto.setUnitOfMeasure(entity.getUnitOfMeasure());
        dto.setDimensionValue(entity.getDimensionValue());
        return dto;
    }
}
