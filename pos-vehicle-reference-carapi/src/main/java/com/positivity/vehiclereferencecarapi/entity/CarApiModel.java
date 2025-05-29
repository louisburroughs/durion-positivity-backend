package com.positivity.vehiclereferencecarapi.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
public class CarApiModel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String modelId;
    private String modelName;
    private String makeId;
    private LocalDateTime cacheTimestamp;
}

