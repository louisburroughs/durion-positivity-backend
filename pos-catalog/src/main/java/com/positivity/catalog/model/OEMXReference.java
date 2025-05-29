package com.positivity.catalog.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Data
public class OEMXReference {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    @ManyToOne
    private  ProductEntity part; // Reference to the product
    @ManyToOne
    private  ProductEntity oemPart; // Reference to the OEM product that matches
}
