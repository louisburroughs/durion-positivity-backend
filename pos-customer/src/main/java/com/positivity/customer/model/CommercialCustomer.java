package com.positivity.customer.model;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity
@DiscriminatorValue("COMMERCIAL")
public class CommercialCustomer extends AbstractCustomer {
    // Additional fields or methods specific to commercial customers can be added here
}

