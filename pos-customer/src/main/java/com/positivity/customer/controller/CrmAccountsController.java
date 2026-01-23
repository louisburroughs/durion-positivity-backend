package com.positivity.customer.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Stub controller implementing CRM account-tier endpoints from the API catalog.
 *
 * Intentionally unimplemented: returns 501 for all operations.
 */
@RestController
@RequestMapping("/v1/crm")
public class CrmAccountsController {

    private static final Logger log = LoggerFactory.getLogger(CrmAccountsController.class);

    @GetMapping("/accounts/{accountId}/tier")
    public ResponseEntity<Void> getAccountTier(@PathVariable String accountId) {
        log.info("Stub getAccountTier accountId={}", accountId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/accounts/tierResolve")
    public ResponseEntity<Void> resolveAccountTiers(@RequestBody(required = false) Object body) {
        log.info("Stub resolveAccountTiers");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
