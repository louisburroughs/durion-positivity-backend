package com.positivity.price.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/price")
public class PriceRestrictionsController {

    private static final Logger log = LoggerFactory.getLogger(PriceRestrictionsController.class);

    @PostMapping("/restrictions:evaluate")
    public ResponseEntity<Object> evaluateRestrictions(@RequestBody(required = false) Object requestBody) {
        log.info("POST /v1/price/restrictions:evaluate");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/restrictions:override")
    public ResponseEntity<Object> overrideRestrictions(@RequestBody(required = false) Object requestBody) {
        log.info("POST /v1/price/restrictions:override");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
