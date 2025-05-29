package com.positivity.catalog.model;

public record Dimension(
    int id,
    DimensionType type,
    String description,
    String unitOfMeasure,
    double value) {
}
