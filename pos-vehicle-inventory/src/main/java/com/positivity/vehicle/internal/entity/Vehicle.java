package com.positivity.vehicle.internal.entity;

import java.util.UUID;

public interface Vehicle {
    UUID getId();

    String getMake();

    String getModel();

    int getYear();

    String getVIN();

    void setVIN(String vin);
}
