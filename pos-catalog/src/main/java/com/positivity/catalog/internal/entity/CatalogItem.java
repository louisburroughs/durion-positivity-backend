package com.positivity.catalog.internal.entity;

import java.util.UUID;

public interface CatalogItem {
    UUID getId();

    void setId(UUID id);

    String getName();

    String getShortDescription();

    String getLongDescription();
}
