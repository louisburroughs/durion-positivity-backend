package com.positivity.poscatalog.dao;

import com.positivity.poscatalog.model.ProductEntity;
import com.positivity.poscatalog.model.ServiceEntity;
import com.positivity.poscatalog.model.NonInventoryProductEntity;
import java.util.List;
import java.util.Optional;

public interface CatalogDao {
    Optional<ProductEntity> findProductById(String id);
    List<ProductEntity> findProductByName(String name);
    Optional<ServiceEntity> findServiceById(String id);
    List<ServiceEntity> findServiceByName(String name);
    Optional<NonInventoryProductEntity> findNonInventoryProductById(String id);
    List<NonInventoryProductEntity> findNonInventoryProductByName(String name);
}
