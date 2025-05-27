package com.positivity.poscustomer.controller;

import com.positivity.poscustomer.model.AbstractCustomer;
import com.positivity.poscustomer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {
    private static final Logger logger = LoggerFactory.getLogger(CustomerController.class);
    private final CustomerRepository customerRepository;

    @GetMapping
    public List<AbstractCustomer> getAllCustomers() {
        logger.info("Fetching all customers");
        return customerRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AbstractCustomer> getCustomerById(@PathVariable Long id) {
        logger.info("Fetching customer with id: {}", id);
        return customerRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<AbstractCustomer> createCustomer(@RequestBody AbstractCustomer customer) {
        logger.info("Creating new customer: {}", customer);
        AbstractCustomer saved = customerRepository.save(customer);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AbstractCustomer> updateCustomer(@PathVariable Long id, @RequestBody AbstractCustomer customer) {
        logger.info("Updating customer with id: {}", id);
        return customerRepository.findById(id)
                .map(existing -> {
                    customer.setId(id);
                    AbstractCustomer updated = customerRepository.save(customer);
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCustomer(@PathVariable Long id) {
        logger.info("Deleting customer with id: {}", id);
        if (customerRepository.existsById(id)) {
            customerRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}

