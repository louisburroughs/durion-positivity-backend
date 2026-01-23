package com.positivity.customer.model;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Deprecated
@Schema(description = "Deprecated commercial customer subtype. Use Party to model organizations.")
public class CommercialCustomer extends AbstractCustomer {
    // Additional fields or methods specific to commercial customers can be added
    // here
}
