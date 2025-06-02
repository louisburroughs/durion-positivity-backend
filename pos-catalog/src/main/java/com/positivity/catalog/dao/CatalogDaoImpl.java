package com.positivity.catalog.dao;

import com.positivity.catalog.model.ProductEntity;
import com.positivity.catalog.model.ServiceEntity;
import com.positivity.catalog.model.NonInventoryProductEntity;
import com.positivity.catalog.model.CatalogEntity;
import com.positivity.catalog.repository.ProductRepository;
import com.positivity.catalog.repository.ServiceRepository;
import com.positivity.catalog.repository.NonInventoryProductRepository;
import com.positivity.catalog.repository.CatalogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

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
    public Optional<ProductEntity> findProductById(Long id) {
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
    public ProductEntity updateProduct(Long id, ProductEntity product) {
        if (productRepo.existsById(id)) {
            product.setId(id); // Ensure ID is set for update
            return productRepo.save(product);
        }
        return null; // Or throw exception
    }
    @Override
    public boolean deleteProduct(Long id) {
        if (productRepo.existsById(id)) {
            productRepo.deleteById(id);
            return true;
        }
        return false;
    }

    // Service methods
    @Override
    public Optional<ServiceEntity> findServiceById(Long id) {
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
    public ServiceEntity updateService(Long id, ServiceEntity service) {
        if (serviceRepo.existsById(id)) {
            service.setId(id); // Ensure ID is set for update
            return serviceRepo.save(service);
        }
        return null; // Or throw exception
    }
    @Override
    public boolean deleteService(Long id) {
        if (serviceRepo.existsById(id)) {
            serviceRepo.deleteById(id);
            return true;
        }
        return false;
    }

    // NonInventoryProduct methods
    @Override
    public Optional<NonInventoryProductEntity> findNonInventoryProductById(Long id) {
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
    public NonInventoryProductEntity updateNonInventoryProduct(Long id, NonInventoryProductEntity nonInventoryProduct) {
        if (nonInventoryProductRepo.existsById(id)) {
            nonInventoryProduct.setId(id); // Ensure ID is set for update
            return nonInventoryProductRepo.save(nonInventoryProduct);
        }
        return null; // Or throw exception
    }
    @Override
    public boolean deleteNonInventoryProduct(Long id) {
        if (nonInventoryProductRepo.existsById(id)) {
            nonInventoryProductRepo.deleteById(id);
            return true;
        }
        return false;
    }

    // Catalog methods
    @Override
    public Optional<CatalogEntity> findCatalogById(Long id) {
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
    public CatalogEntity updateCatalog(Long id, CatalogEntity catalog) {
        if (catalogRepository.existsById(id)) {
            catalog.setId(id);
            return catalogRepository.save(catalog);
        }
        return null; // Or throw an exception
    }

    @Override
    public boolean deleteCatalog(Long id) {
        if (catalogRepository.existsById(id)) {
            catalogRepository.deleteById(id);
            return true;
        }
        return false;
    }
}
