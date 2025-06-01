package com.positivity.catalog.controller;

import com.positivity.catalog.dao.CatalogDao;
import com.positivity.catalog.model.ProductEntity;
import com.positivity.catalog.model.ServiceEntity;
import com.positivity.catalog.model.NonInventoryProductEntity;
import com.positivity.catalog.model.CatalogEntity;
import com.positivity.catalog.CatalogItem;
import com.positivity.posevents.EmitEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    public static final String UNSUPPORTED_ITEM_TYPE = "Unsupported item type";
    private final CatalogDao catalogDao;

    @EmitEvent(id = "Catalog-000001-0000000001")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/product/id/{id}")
    public ResponseEntity<ProductEntity> getProductById(@PathVariable String id) {
        Optional<ProductEntity> product = catalogDao.findProductById(id);
        return product.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @EmitEvent(id = "Catalog-000001-0000000002")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/product/name/{name}")
    public List<ProductEntity> getProductByName(@PathVariable String name) {
        return catalogDao.findProductByName(name);
    }

    @EmitEvent(id = "Catalog-000001-0000000003")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/service/id/{id}")
    public ResponseEntity<ServiceEntity> getServiceById(@PathVariable String id) {
        Optional<ServiceEntity> service = catalogDao.findServiceById(id);
        return service.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @EmitEvent(id = "Catalog-000001-0000000004")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/service/name/{name}")
    public List<ServiceEntity> getServiceByName(@PathVariable String name) {
        return catalogDao.findServiceByName(name);
    }

    @EmitEvent(id = "Catalog-000001-0000000005")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/noninventory/id/{id}")
    public ResponseEntity<NonInventoryProductEntity> getNonInventoryProductById(@PathVariable String id) {
        Optional<NonInventoryProductEntity> item = catalogDao.findNonInventoryProductById(id);
        return item.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @EmitEvent(id = "Catalog-000001-0000000006")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/noninventory/name/{name}")
    public List<NonInventoryProductEntity> getNonInventoryProductByName(@PathVariable String name) {
        return catalogDao.findNonInventoryProductByName(name);
    }

    @EmitEvent(id = "Catalog-000001-0000000007")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/id/{id}")
    public ResponseEntity<CatalogEntity> getCatalogById(@PathVariable Long id) {
        Optional<CatalogEntity> catalog = catalogDao.findCatalogById(id);
        return catalog.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @EmitEvent(id = "Catalog-000001-0000000009")
    @GetMapping("/name/{name}")
    public ResponseEntity<CatalogEntity> getCatalogByName(@PathVariable String name) {
        Optional<CatalogEntity> catalog = catalogDao.findCatalogByName(name);
        return catalog.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @EmitEvent(id = "Catalog-000001-0000000010")
    @DeleteMapping("/id/{id}")
    public ResponseEntity<Void> deleteCatalogById(@PathVariable Long id) {
        catalogDao.deleteCatalogById(id);
        return ResponseEntity.noContent().build();
    }

    @EmitEvent(id = "Catalog-000001-0000000011")
    @DeleteMapping("/name/{name}")
    public ResponseEntity<Void> deleteCatalogByName(@PathVariable String name) {
        catalogDao.deleteCatalogByName(name);
        return ResponseEntity.noContent().build();
    }

    @EmitEvent(id = "Catalog-000001-0000000012")
    @PostMapping("/{catalogId}/add-item")
    public ResponseEntity<String> addItemToCatalog(@PathVariable Long catalogId, @RequestBody CatalogItem item) {
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
                return ResponseEntity.badRequest().body(UNSUPPORTED_ITEM_TYPE);
            }
        }
        catalogDao.saveCatalog(catalog);
        return ResponseEntity.ok().build();
    }

    @EmitEvent(id = "Catalog-000001-0000000012")
    @PutMapping("/{catalogId}/update-item")
    public ResponseEntity<Object> updateItemInCatalog(@PathVariable Long catalogId, @RequestBody CatalogItem item) {
        return catalogDao.findCatalogById(catalogId)
            .map(catalog -> {
                boolean updated = updateCatalogItem(catalog, item);
                if (!updated) {
                    return ResponseEntity.notFound().build();
                }
                catalogDao.saveCatalog(catalog);
                return ResponseEntity.ok().build();
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private boolean updateCatalogItem(CatalogEntity catalog, CatalogItem item) {
        switch (item) {
            case ProductEntity productEntity -> {
                return updateItemInList(catalog.getProducts(), productEntity);
            }
            case ServiceEntity serviceEntity -> {
                return updateItemInList(catalog.getServices(), serviceEntity);
            }
            case NonInventoryProductEntity nonInventoryProductEntity -> {
                return updateItemInList(catalog.getNonInventoryProducts(), nonInventoryProductEntity);
            }
            default ->
                throw new IllegalArgumentException(UNSUPPORTED_ITEM_TYPE);
        }
    }

    private <T extends CatalogItem> boolean updateItemInList(List<T> items, T newItem) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId().equals(newItem.getId())) {
                items.set(i, newItem);
                return true;
            }
        }
        return false;
    }

    @EmitEvent(id = "Catalog-000001-0000000013")
    @DeleteMapping("/{catalogId}/remove-item/{itemType}/{itemId}")
    public ResponseEntity<String> removeItemFromCatalog(@PathVariable Long catalogId, @PathVariable String itemType, @PathVariable Long itemId) {
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
                return ResponseEntity.badRequest().body(UNSUPPORTED_ITEM_TYPE);
        }
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        catalogDao.saveCatalog(catalog);
        return ResponseEntity.ok().build();
    }
}
