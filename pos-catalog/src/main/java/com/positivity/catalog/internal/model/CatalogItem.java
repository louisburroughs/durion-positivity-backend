package com.positivity.catalog.internal.model;

public interface CatalogItem {
    Long getId();

    void setId(Long id);

    String getName();

    String getShortDescription();

    String getLongDescription();
}
