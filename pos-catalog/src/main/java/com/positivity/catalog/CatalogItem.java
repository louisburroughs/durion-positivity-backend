package com.positivity.catalog;

public interface CatalogItem {
    Long getId();
    void setId(Long id);
    String getName();
    String getShortDescription();
    String getLongDescription();
}
