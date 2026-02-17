package com.positivity.catalog.internal.dao;

import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.ServiceRepository;
import com.positivity.catalog.internal.repository.NonInventoryProductRepository;
import com.positivity.catalog.internal.entity.CatalogEntity;
import com.positivity.catalog.internal.entity.NonInventoryProductEntity;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.repository.CatalogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CatalogDaoImpl implements CatalogDao {
    private final ProductRepository productRepo;
    private final ServiceRepository serviceRepo;
    private final NonInventoryProductRepository nonInventoryProductRepo;
    private final CatalogRepository catalogRepository;

    // Product methods
    @Override
    public Optional<ProductEntity> findProductById(UUID id) {
        return productRepo.findById(id);
    }

    @Override
    public List<ProductEntity> findProductByName(String name) {
        return productRepo.findByName(name);
    }

    @Override
    public ProductEntity saveProduct(ProductEntity product) {
        return productRepo.save(product);
    }

    @Override
    public ProductEntity updateProduct(UUID id, ProductEntity product) {
        if (productRepo.existsById(id)) {
            product.setId(id); // Ensure ID is set for update
            return productRepo.save(product);
        }
        return null; // Or throw exception
    }

    @Override
    public boolean deleteProduct(UUID id) {
        if (productRepo.existsById(id)) {
            productRepo.deleteById(id);
            return true;
        }
        return false;
    }

    // Service methods
    @Override
    public Optional<ServiceEntity> findServiceById(UUID id) {
        return serviceRepo.findById(id);
    }

    @Override
    public List<ServiceEntity> findServiceByName(String name) {
        return serviceRepo.findByName(name);
    }

    @Override
    public ServiceEntity saveService(ServiceEntity service) {
        return serviceRepo.save(service);
    }

    @Override
    public ServiceEntity updateService(UUID id, ServiceEntity service) {
        if (serviceRepo.existsById(id)) {
            service.setId(id); // Ensure ID is set for update
            return serviceRepo.save(service);
        }
        return null; // Or throw exception
    }

    @Override
    public boolean deleteService(UUID id) {
        if (serviceRepo.existsById(id)) {
            serviceRepo.deleteById(id);
            return true;
        }
        return false;
    }

    // NonInventoryProduct methods
    @Override
    public Optional<NonInventoryProductEntity> findNonInventoryProductById(UUID id) {
        return nonInventoryProductRepo.findById(id);
    }

    @Override
    public List<NonInventoryProductEntity> findNonInventoryProductByName(String name) {
        return nonInventoryProductRepo.findByName(name);
    }

    @Override
    public NonInventoryProductEntity saveNonInventoryProduct(NonInventoryProductEntity nonInventoryProduct) {
        return nonInventoryProductRepo.save(nonInventoryProduct);
    }

    @Override
    public NonInventoryProductEntity updateNonInventoryProduct(UUID id, NonInventoryProductEntity nonInventoryProduct) {
        if (nonInventoryProductRepo.existsById(id)) {
            nonInventoryProduct.setId(id); // Ensure ID is set for update
            return nonInventoryProductRepo.save(nonInventoryProduct);
        }
        return null; // Or throw exception
    }

    @Override
    public boolean deleteNonInventoryProduct(UUID id) {
        if (nonInventoryProductRepo.existsById(id)) {
            nonInventoryProductRepo.deleteById(id);
            return true;
        }
        return false;
    }

    // Catalog methods
    @Override
    public Optional<CatalogEntity> findCatalogById(UUID id) {
        return catalogRepository.findById(id);
    }

    @Override
    public List<CatalogEntity> findCatalogByName(String name) {
        return catalogRepository.findByNameContainingIgnoreCase(name);
    }

    @Override
    public List<CatalogEntity> findAllCatalogs() {
        return catalogRepository.findAll();
    }

    @Override
    public CatalogEntity saveCatalog(CatalogEntity catalog) {
        return catalogRepository.save(catalog);
    }

    @Override
    public CatalogEntity updateCatalog(UUID id, CatalogEntity catalog) {
        if (catalogRepository.existsById(id)) {
            catalog.setId(id);
            return catalogRepository.save(catalog);
        }
        return null; // Or throw an exception
    }

    @Override
    public boolean deleteCatalog(UUID id) {
        if (catalogRepository.existsById(id)) {
            catalogRepository.deleteById(id);
            return true;
        }
        return false;
    }
}
