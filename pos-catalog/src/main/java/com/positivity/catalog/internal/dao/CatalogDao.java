package com.positivity.catalog.internal.dao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.positivity.catalog.internal.entity.CatalogEntity;
import com.positivity.catalog.internal.entity.NonInventoryProductEntity;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ServiceEntity;

public interface CatalogDao {
    // Product methods
    Optional<ProductEntity> findProductById(UUID id);

    List<ProductEntity> findProductByName(String name);

    ProductEntity saveProduct(ProductEntity product);

    ProductEntity updateProduct(UUID id, ProductEntity product);

    boolean deleteProduct(UUID id);

    // Service methods
    Optional<ServiceEntity> findServiceById(UUID id);

    List<ServiceEntity> findServiceByName(String name);

    ServiceEntity saveService(ServiceEntity service);

    ServiceEntity updateService(UUID id, ServiceEntity service);

    boolean deleteService(UUID id);

    // NonInventoryProduct methods
    Optional<NonInventoryProductEntity> findNonInventoryProductById(UUID id);

    List<NonInventoryProductEntity> findNonInventoryProductByName(String name);

    NonInventoryProductEntity saveNonInventoryProduct(NonInventoryProductEntity nonInventoryProduct);

    NonInventoryProductEntity updateNonInventoryProduct(UUID id, NonInventoryProductEntity nonInventoryProduct);

    boolean deleteNonInventoryProduct(UUID id);

    // Catalog methods
    Optional<CatalogEntity> findCatalogById(UUID id); // ID is UUID for CatalogEntity

    List<CatalogEntity> findCatalogByName(String name); // Changed from Optional to List

    List<CatalogEntity> findAllCatalogs();

    CatalogEntity saveCatalog(CatalogEntity catalog); // Changed return type from void

    CatalogEntity updateCatalog(UUID id, CatalogEntity catalog); // New method

    boolean deleteCatalog(UUID id); // New method, replaces deleteCatalogById

    // Methods to be removed if not used elsewhere or replaced:
    // void deleteCatalogById(UUID id); // Replaced by deleteCatalog(UUID id)
    // void deleteCatalogByName(String name); // Controller uses ID for deletion
}
