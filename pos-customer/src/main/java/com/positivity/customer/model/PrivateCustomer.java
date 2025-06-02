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
@DiscriminatorValue("PRIVATE")
public class PrivateCustomer extends AbstractCustomer {
    // Additional fields or methods specific to private customers can be added here
}

