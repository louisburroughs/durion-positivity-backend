package com.positivity.poscatalog;

public class NonInventoryProduct implements CatalogItem {
    private final String id;
    private final String name;
    private final String description;

    public NonInventoryProduct(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }
    @Override
    public String getId() { return id; }
    @Override
    public String getName() { return name; }
    @Override
    public String getDescription() { return description; }
}
