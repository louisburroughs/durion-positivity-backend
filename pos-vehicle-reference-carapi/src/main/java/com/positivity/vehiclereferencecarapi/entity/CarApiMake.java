package com.positivity.vehiclereferencecarapi.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
public class CarApiMake {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String makeId;
    private String makeName;
    private LocalDateTime cacheTimestamp;
}

