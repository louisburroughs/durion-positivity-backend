package com.positivity.customer.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.positivity.customer.internal.dto.CustomerDTO;

public interface CustomerService {

    /**
     * Retrieves all customers as DTOs.
     *
     * @return list of all customers as DTOs
     */
    List<CustomerDTO> getAllCustomers();

    /**
     * Retrieves all person customers as DTOs with paging.
     *
     * @param pageable pagination information
     * @return page of person customers as DTOs
     */
    Page<CustomerDTO> getAllCustomers(Pageable pageable);

    /**
     * Retrieves a customer by ID as DTO.
     *
     * @param id the customer ID
     * @return optional containing the customer DTO if found
     */
    Optional<CustomerDTO> getCustomerById(UUID id);

    /**
     * Creates a new customer from DTO.
     *
     * @param dto the customer DTO to create
     * @return the saved customer as DTO
     */
    CustomerDTO createCustomer(CustomerDTO dto);

    /**
     * Updates an existing customer from DTO.
     *
     * @param id  the customer ID
     * @param dto the updated customer DTO
     * @return optional containing the updated customer DTO if found
     */
    Optional<CustomerDTO> updateCustomer(UUID id, CustomerDTO dto);

    /**
     * Deletes a customer by ID.
     *
     * @param id the customer ID
     * @return true if customer was deleted, false if not found
     */
    boolean deleteCustomer(UUID id);

    /**
     * Checks if a customer exists by ID.
     *
     * @param id the customer ID
     * @return true if customer exists
     */
    boolean existsById(UUID id);

}