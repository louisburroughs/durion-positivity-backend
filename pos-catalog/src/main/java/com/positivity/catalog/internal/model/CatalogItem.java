package com.positivity.catalog.internal.model;

import java.util.UUID;

public interface CatalogItem {
    UUID getId();

    void setId(UUID id);

    String getName();

    String getShortDescription();

    String getLongDescription();
}
