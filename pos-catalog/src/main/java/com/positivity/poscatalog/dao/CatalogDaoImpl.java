package com.positivity.poscatalog.dao;

import com.positivity.poscatalog.model.ProductEntity;
import com.positivity.poscatalog.model.ServiceEntity;
import com.positivity.poscatalog.model.NonInventoryProductEntity;
import com.positivity.poscatalog.repository.ProductRepository;
import com.positivity.poscatalog.repository.ServiceRepository;
import com.positivity.poscatalog.repository.NonInventoryProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class CatalogDaoImpl implements CatalogDao {
    private final ProductRepository productRepo;
    private final ServiceRepository serviceRepo;
    private final NonInventoryProductRepository nonInventoryProductRepo;

    @Autowired
    public CatalogDaoImpl(ProductRepository productRepo, ServiceRepository serviceRepo, NonInventoryProductRepository nonInventoryProductRepo) {
        this.productRepo = productRepo;
        this.serviceRepo = serviceRepo;
        this.nonInventoryProductRepo = nonInventoryProductRepo;
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
}
