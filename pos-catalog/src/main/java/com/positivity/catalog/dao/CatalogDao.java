package com.positivity.catalog.dao;

import com.positivity.catalog.model.ProductEntity;
import com.positivity.catalog.model.ServiceEntity;
import com.positivity.catalog.model.NonInventoryProductEntity;
import com.positivity.catalog.model.CatalogEntity;
import java.util.List;
import java.util.Optional;

public interface CatalogDao {
    Optional<ProductEntity> findProductById(String id);
    List<ProductEntity> findProductByName(String name);
    Optional<ServiceEntity> findServiceById(String id);
    List<ServiceEntity> findServiceByName(String name);
    Optional<NonInventoryProductEntity> findNonInventoryProductById(String id);
    List<NonInventoryProductEntity> findNonInventoryProductByName(String name);
    Optional<CatalogEntity> findCatalogById(Long id);
    Optional<CatalogEntity> findCatalogByName(String name);
    void deleteCatalogById(Long id);
    void deleteCatalogByName(String name);
    List<CatalogEntity> findAllCatalogs();
    void saveCatalog(CatalogEntity catalog);
}
