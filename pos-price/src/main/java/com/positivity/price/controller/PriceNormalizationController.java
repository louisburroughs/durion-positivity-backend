package com.positivity.price.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/price")
public class PriceNormalizationController {

    private static final Logger log = LoggerFactory.getLogger(PriceNormalizationController.class);

    @PostMapping("/normalize")
    public ResponseEntity<Object> normalizePricing(@RequestBody(required = false) Object requestBody) {
        log.info("POST /v1/price/normalize");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
