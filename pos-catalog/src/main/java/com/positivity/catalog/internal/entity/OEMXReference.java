package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Getter
@Setter
@Data
public class OEMXReference {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne
    private ProductEntity part; // Reference to the product
    @ManyToOne
    private ProductEntity oemPart; // Reference to the OEM product that matches
}
