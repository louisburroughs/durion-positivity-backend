package com.positivity.poscatalog.model;

import java.util.List;


public class CatalogEntity {
    private List<ProductEntity> products;
    private List<ServiceEntity> services;
    private List<NonInventoryProductEntity> nonInventoryProducts;

    public CatalogEntity(List<ProductEntity> products, List<ServiceEntity> services, List<NonInventoryProductEntity> nonInventoryProducts) {
        this.products = products;
        this.services = services;
        this.nonInventoryProducts = nonInventoryProducts;
    }

    public List<ProductEntity> getProducts() { return products; }
    public void setProducts(List<ProductEntity> products) { this.products = products; }
    public List<ServiceEntity> getServices() { return services; }
    public void setServices(List<ServiceEntity> services) { this.services = services; }
    public List<NonInventoryProductEntity> getNonInventoryProducts() { return nonInventoryProducts; }
    public void setNonInventoryProducts(List<NonInventoryProductEntity> nonInventoryProducts) { this.nonInventoryProducts = nonInventoryProducts; }
}
