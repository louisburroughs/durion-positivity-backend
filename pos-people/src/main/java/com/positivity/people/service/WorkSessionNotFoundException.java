package com.positivity.people.service;

public class WorkSessionNotFoundException extends RuntimeException {

    public WorkSessionNotFoundException(String message) {
        super(message);
    }
}