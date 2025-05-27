package com.positivity.poscustomer.model;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity
@DiscriminatorValue("GOVERNMENT")
public class GovernmentCustomer extends AbstractCustomer {
    // Additional fields or methods specific to government customers can be added here
}

