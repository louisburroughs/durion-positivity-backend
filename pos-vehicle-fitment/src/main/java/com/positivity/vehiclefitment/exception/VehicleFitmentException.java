package com.positivity.vehiclefitment.exception;

public class VehicleFitmentException extends RuntimeException {
    public VehicleFitmentException(String message) {
        super(message);
    }
    public VehicleFitmentException(String message, Throwable cause) {
        super(message, cause);
    }
}

