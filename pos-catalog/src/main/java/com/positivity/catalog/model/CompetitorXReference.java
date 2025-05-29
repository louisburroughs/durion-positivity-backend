package com.positivity.catalog.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Data
public class CompetitorXReference {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    @ManyToOne
    private  ProductEntity part; // Reference to the product
    @ManyToOne
    private  ProductEntity competitorPart; // Reference to the Competitor product that matches
}
