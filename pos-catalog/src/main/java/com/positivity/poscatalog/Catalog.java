package com.positivity.poscatalog;

import java.util.List;

public class Catalog {
    private List<Product> products;
    private List<Service> services;
    private List<NonInventoryProduct> nonInventoryProducts;

    public Catalog(List<Product> products, List<Service> services, List<NonInventoryProduct> nonInventoryProducts) {
        this.products = products;
        this.services = services;
        this.nonInventoryProducts = nonInventoryProducts;
    }

    public List<Product> getProducts() { return products; }
    public void setProducts(List<Product> products) { this.products = products; }
    public List<Service> getServices() { return services; }
    public void setServices(List<Service> services) { this.services = services; }
    public List<NonInventoryProduct> getNonInventoryProducts() { return nonInventoryProducts; }
    public void setNonInventoryProducts(List<NonInventoryProduct> nonInventoryProducts) { this.nonInventoryProducts = nonInventoryProducts; }
}
