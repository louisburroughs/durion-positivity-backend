package com.positivity.customer.internal.entity;

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
@DiscriminatorValue("GOVERNMENT")
@Deprecated
@Schema(description = "Deprecated government customer subtype. Use Party to model organizations.")
public class GovernmentCustomer extends AbstractCustomer {
    // Additional fields or methods specific to government customers can be added
    // here
}
