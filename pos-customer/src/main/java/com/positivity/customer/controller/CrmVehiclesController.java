package com.positivity.customer.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Stub controller implementing CRM vehicle endpoints from the API catalog.
 *
 * Intentionally unimplemented: returns 501 for all operations.
 */
@RestController
@RequestMapping("/v1/crm")
public class CrmVehiclesController {

    private static final Logger log = LoggerFactory.getLogger(CrmVehiclesController.class);

    @PostMapping("/{customerId}/vehicles")
    public ResponseEntity<Void> createVehicles(
            @PathVariable String customerId,
            @RequestBody(required = false) Object body) {
        log.info("Stub createVehicles customerId={}", customerId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PutMapping("/{customerId}/vehicles")
    public ResponseEntity<Void> updateVehicles(
            @PathVariable String customerId,
            @RequestBody(required = false) Object body) {
        log.info("Stub updateVehicles customerId={}", customerId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @DeleteMapping("/{customerId}/vehicles/{vehicleId}")
    public ResponseEntity<Void> deleteVehicle(
            @PathVariable String customerId,
            @PathVariable String vehicleId) {
        log.info("Stub deleteVehicle customerId={} vehicleId={}", customerId, vehicleId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PutMapping("/{customerId}/vehicles/{vehicleId}/transfer")
    public ResponseEntity<Void> transferVehicles(
            @PathVariable String customerId,
            @PathVariable String vehicleId,
            @RequestBody(required = false) Object body) {
        log.info("Stub transferVehicles customerId={} vehicleId={}", customerId, vehicleId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/vehicles/{vehicleId}")
    public ResponseEntity<Void> getVehicle(@PathVariable String vehicleId) {
        log.info("Stub getVehicle vehicleId={}", vehicleId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/vehicles")
    public ResponseEntity<Void> getAllVehiclesFiltered() {
        log.info("Stub getAllVehiclesFiltered");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/{customerId}/vehicles/{vehicleId}")
    public ResponseEntity<Void> getVehiclesForCustomer(
            @PathVariable String customerId,
            @PathVariable String vehicleId) {
        log.info("Stub getVehiclesForCustomer customerId={} vehicleId={}", customerId, vehicleId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
