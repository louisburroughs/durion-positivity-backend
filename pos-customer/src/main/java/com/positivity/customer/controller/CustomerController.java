package com.positivity.customer.controller;

import com.positivity.customer.model.AbstractCustomer;
import com.positivity.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Slf4j
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {
    
    private final CustomerRepository customerRepository;

    @GetMapping
    public List<AbstractCustomer> getAllCustomers() {
        log.info("Fetching all customers");
        return customerRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AbstractCustomer> getCustomerById(@PathVariable Long id) {
        log.info("Fetching customer with id: {}", id);
        return customerRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<AbstractCustomer> createCustomer(@RequestBody AbstractCustomer customer) {
        log.info("Creating new customer: {}", customer);
        AbstractCustomer saved = customerRepository.save(customer);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AbstractCustomer> updateCustomer(@PathVariable Long id, @RequestBody AbstractCustomer customer) {
        log.info("Updating customer with id: {}", id);
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
        log.info("Deleting customer with id: {}", id);
        if (customerRepository.existsById(id)) {
            customerRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}

