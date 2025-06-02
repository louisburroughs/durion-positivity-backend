package com.positivity.catalog.dao;

import com.positivity.catalog.model.ProductEntity;
import com.positivity.catalog.model.ServiceEntity;
import com.positivity.catalog.model.NonInventoryProductEntity;
import com.positivity.catalog.model.CatalogEntity;
import java.util.List;
import java.util.Optional;

public interface CatalogDao {
    // Product methods
    Optional<ProductEntity> findProductById(Long id);
    List<ProductEntity> findProductByName(String name);
    ProductEntity saveProduct(ProductEntity product);
    ProductEntity updateProduct(Long id, ProductEntity product);
    boolean deleteProduct(Long id);

    // Service methods
    Optional<ServiceEntity> findServiceById(Long id);
    List<ServiceEntity> findServiceByName(String name);
    ServiceEntity saveService(ServiceEntity service);
    ServiceEntity updateService(Long id, ServiceEntity service);
    boolean deleteService(Long id);

    // NonInventoryProduct methods
    Optional<NonInventoryProductEntity> findNonInventoryProductById(Long id);
    List<NonInventoryProductEntity> findNonInventoryProductByName(String name);
    NonInventoryProductEntity saveNonInventoryProduct(NonInventoryProductEntity nonInventoryProduct);
    NonInventoryProductEntity updateNonInventoryProduct(Long id, NonInventoryProductEntity nonInventoryProduct);
    boolean deleteNonInventoryProduct(Long id);

    // Catalog methods
    Optional<CatalogEntity> findCatalogById(Long id); // ID is Long for CatalogEntity
    List<CatalogEntity> findCatalogByName(String name); // Changed from Optional to List
    List<CatalogEntity> findAllCatalogs();
    CatalogEntity saveCatalog(CatalogEntity catalog); // Changed return type from void
    CatalogEntity updateCatalog(Long id, CatalogEntity catalog); // New method
    boolean deleteCatalog(Long id); // New method, replaces deleteCatalogById

    // Methods to be removed if not used elsewhere or replaced:
    // void deleteCatalogById(Long id); // Replaced by deleteCatalog(Long id)
    // void deleteCatalogByName(String name); // Controller uses ID for deletion
}
