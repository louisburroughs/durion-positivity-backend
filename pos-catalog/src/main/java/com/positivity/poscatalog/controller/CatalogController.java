package com.positivity.poscatalog.controller;

import com.positivity.poscatalog.dao.CatalogDao;
import com.positivity.poscatalog.model.ProductEntity;
import com.positivity.poscatalog.model.ServiceEntity;
import com.positivity.poscatalog.model.NonInventoryProductEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private final CatalogDao catalogDao;

    @Autowired
    public CatalogController(CatalogDao catalogDao) {
        this.catalogDao = catalogDao;
    }

    @GetMapping("/product/id/{id}")
    public ResponseEntity<ProductEntity> getProductById(@PathVariable String id) {
        Optional<ProductEntity> product = catalogDao.findProductById(id);
        return product.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/product/name/{name}")
    public List<ProductEntity> getProductByName(@PathVariable String name) {
        return catalogDao.findProductByName(name);
    }

    @GetMapping("/service/id/{id}")
    public ResponseEntity<ServiceEntity> getServiceById(@PathVariable String id) {
        Optional<ServiceEntity> service = catalogDao.findServiceById(id);
        return service.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/service/name/{name}")
    public List<ServiceEntity> getServiceByName(@PathVariable String name) {
        return catalogDao.findServiceByName(name);
    }

    @GetMapping("/noninventory/id/{id}")
    public ResponseEntity<NonInventoryProductEntity> getNonInventoryProductById(@PathVariable String id) {
        Optional<NonInventoryProductEntity> item = catalogDao.findNonInventoryProductById(id);
        return item.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/noninventory/name/{name}")
    public List<NonInventoryProductEntity> getNonInventoryProductByName(@PathVariable String name) {
        return catalogDao.findNonInventoryProductByName(name);
    }
}
