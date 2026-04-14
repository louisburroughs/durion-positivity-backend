package com.positivity.shopmanager.internal.dto;

import lombok.Data;

@Data
public class ServiceEntityDTO {
    private Long id;
    private String name;
    private String description;
    // Add other fields as needed from pos-catalog ServiceEntity
}
