package com.positivity.catalog.internal.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductSearchResultDto {
    private List<ProductDto> items;
    private int page;
    private int size;
    private long total;
}
