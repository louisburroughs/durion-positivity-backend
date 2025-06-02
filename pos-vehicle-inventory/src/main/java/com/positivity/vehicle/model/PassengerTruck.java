package com.positivity.vehicle.model;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
@DiscriminatorValue("PASSENGER_TRUCK")
public class PassengerTruck extends VehicleEntity {}
