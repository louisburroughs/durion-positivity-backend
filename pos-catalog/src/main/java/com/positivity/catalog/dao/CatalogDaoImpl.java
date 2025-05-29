package com.positivity.catalog.dao;

import com.positivity.catalog.model.ProductEntity;
import com.positivity.catalog.model.ServiceEntity;
import com.positivity.catalog.model.NonInventoryProductEntity;
import com.positivity.catalog.model.CatalogEntity;
import com.positivity.catalog.repository.ProductRepository;
import com.positivity.catalog.repository.ServiceRepository;
import com.positivity.catalog.repository.NonInventoryProductRepository;
import com.positivity.catalog.repository.CatalogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
public class CatalogDaoImpl implements CatalogDao {
    private final ProductRepository productRepo;
    private final ServiceRepository serviceRepo;
    private final NonInventoryProductRepository nonInventoryProductRepo;
    private final CatalogRepository catalogRepository;

    public CatalogDaoImpl(ProductRepository productRepo, ServiceRepository serviceRepo, NonInventoryProductRepository nonInventoryProductRepo, CatalogRepository catalogRepository) {
        this.productRepo = productRepo;
        this.serviceRepo = serviceRepo;
        this.nonInventoryProductRepo = nonInventoryProductRepo;
        this.catalogRepository = catalogRepository;
    }

    @Override
    public Optional<ProductEntity> findProductById(String id) {
        return productRepo.findById(id);
    }
    @Override
    public List<ProductEntity> findProductByName(String name) {
        return productRepo.findByName(name);
    }
    @Override
    public Optional<ServiceEntity> findServiceById(String id) {
        return serviceRepo.findById(id);
    }
    @Override
    public List<ServiceEntity> findServiceByName(String name) {
        return serviceRepo.findByName(name);
    }
    @Override
    public Optional<NonInventoryProductEntity> findNonInventoryProductById(String id) {
        return nonInventoryProductRepo.findById(id);
    }
    @Override
    public List<NonInventoryProductEntity> findNonInventoryProductByName(String name) {
        return nonInventoryProductRepo.findByName(name);
    }
    @Override
    public Optional<CatalogEntity> findCatalogById(Long id) {
        return catalogRepository.findById(id);
    }
    @Override
    public Optional<CatalogEntity> findCatalogByName(String name) {
        return catalogRepository.findByName(name);
    }
    @Override
    public void deleteCatalogById(Long id) {
        catalogRepository.deleteById(id);
    }
    @Override
    public void deleteCatalogByName(String name) {
        catalogRepository.deleteByName(name);
    }
    @Override
    public List<CatalogEntity> findAllCatalogs() {
        return catalogRepository.findAll();
    }
    @Override
    public void saveCatalog(CatalogEntity catalog) {
        catalogRepository.save(catalog);
    }
}
