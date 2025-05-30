package com.positivity.catalog.controller;

import com.positivity.catalog.dao.CatalogDao;
import com.positivity.catalog.model.ProductEntity;
import com.positivity.catalog.model.ServiceEntity;
import com.positivity.catalog.model.NonInventoryProductEntity;
import com.positivity.catalog.model.CatalogEntity;
import com.positivity.catalog.CatalogItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private final CatalogDao catalogDao;

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

    @GetMapping("/id/{id}")
    public ResponseEntity<CatalogEntity> getCatalogById(@PathVariable Long id) {
        Optional<CatalogEntity> catalog = catalogDao.findCatalogById(id);
        return catalog.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/name/{name}")
    public ResponseEntity<CatalogEntity> getCatalogByName(@PathVariable String name) {
        Optional<CatalogEntity> catalog = catalogDao.findCatalogByName(name);
        return catalog.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/id/{id}")
    public ResponseEntity<Void> deleteCatalogById(@PathVariable Long id) {
        catalogDao.deleteCatalogById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/name/{name}")
    public ResponseEntity<Void> deleteCatalogByName(@PathVariable String name) {
        catalogDao.deleteCatalogByName(name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{catalogId}/add-item")
    public ResponseEntity<?> addItemToCatalog(@PathVariable Long catalogId, @RequestBody CatalogItem item) {
        Optional<CatalogEntity> catalogOpt = catalogDao.findCatalogById(catalogId);
        if (catalogOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CatalogEntity catalog = catalogOpt.get();
        switch (item) {
            case ProductEntity productEntity -> catalog.getProducts().add(productEntity);
            case ServiceEntity serviceEntity -> catalog.getServices().add(serviceEntity);
            case NonInventoryProductEntity nonInventoryProductEntity ->
                    catalog.getNonInventoryProducts().add(nonInventoryProductEntity);
            case null, default -> {
                return ResponseEntity.badRequest().body("Unsupported item type");
            }
        }
        catalogDao.saveCatalog(catalog);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{catalogId}/update-item")
    public ResponseEntity<?> updateItemInCatalog(@PathVariable Long catalogId, @RequestBody CatalogItem item) {
        Optional<CatalogEntity> catalogOpt = catalogDao.findCatalogById(catalogId);
        if (catalogOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CatalogEntity catalog = catalogOpt.get();
        boolean updated = false;
        switch (item) {
            case ProductEntity productEntity -> {
                List<ProductEntity> products = catalog.getProducts();
                for (int i = 0; i < products.size(); i++) {
                    if (products.get(i).getId().equals(item.getId())) {
                        products.set(i, productEntity);
                        updated = true;
                        break;
                    }
                }
            }
            case ServiceEntity serviceEntity -> {
                List<ServiceEntity> services = catalog.getServices();
                for (int i = 0; i < services.size(); i++) {
                    if (services.get(i).getId().equals(item.getId())) {
                        services.set(i, serviceEntity);
                        updated = true;
                        break;
                    }
                }
            }
            case NonInventoryProductEntity nonInventoryProductEntity -> {
                List<NonInventoryProductEntity> nonInventoryProducts = catalog.getNonInventoryProducts();
                for (int i = 0; i < nonInventoryProducts.size(); i++) {
                    if (nonInventoryProducts.get(i).getId().equals(item.getId())) {
                        nonInventoryProducts.set(i, nonInventoryProductEntity);
                        updated = true;
                        break;
                    }
                }
            }
            default -> {
                return ResponseEntity.badRequest().body("Unsupported item type");
            }
        }
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        catalogDao.saveCatalog(catalog);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{catalogId}/remove-item/{itemType}/{itemId}")
    public ResponseEntity<?> removeItemFromCatalog(@PathVariable Long catalogId, @PathVariable String itemType, @PathVariable Long itemId) {
        Optional<CatalogEntity> catalogOpt = catalogDao.findCatalogById(catalogId);
        if (catalogOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CatalogEntity catalog = catalogOpt.get();
        boolean removed = false;
        switch (itemType.toLowerCase()) {
            case "product":
                removed = catalog.getProducts().removeIf(p -> p.getId().equals(itemId));
                break;
            case "service":
                removed = catalog.getServices().removeIf(s -> s.getId().equals(itemId));
                break;
            case "noninventory":
                removed = catalog.getNonInventoryProducts().removeIf(n -> n.getId().equals(itemId));
                break;
            default:
                return ResponseEntity.badRequest().body("Unsupported item type");
        }
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        catalogDao.saveCatalog(catalog);
        return ResponseEntity.ok().build();
    }
}
