package com.positivity.vehicle.internal.model;

public interface Vehicle {
    Long getId();

    String getMake();

    String getModel();

    int getYear();

    String getVIN();

    void setVIN(String vin);
}
