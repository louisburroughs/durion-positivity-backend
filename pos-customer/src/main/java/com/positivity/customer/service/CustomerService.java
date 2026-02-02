package com.positivity.customer.service;

import com.positivity.customer.internal.dto.CustomerDTO;
import com.positivity.customer.internal.entity.AbstractCustomer;
import com.positivity.customer.internal.entity.CommercialCustomer;
import com.positivity.customer.internal.entity.GovernmentCustomer;
import com.positivity.customer.internal.entity.PrivateCustomer;
import com.positivity.customer.internal.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Service for managing Customer entities.
 * Provides business logic layer between controllers and repositories.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;

    /**
     * Retrieves all customers as DTOs.
     *
     * @return list of all customers as DTOs
     */
    @Transactional(readOnly = true)
    public List<CustomerDTO> getAllCustomers() {
        log.debug("Fetching all customers");
        return customerRepository.findAll().stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * Retrieves a customer by ID as DTO.
     *
     * @param id the customer ID
     * @return optional containing the customer DTO if found
     */
    @Transactional(readOnly = true)
    public Optional<CustomerDTO> getCustomerById(@NonNull Long id) {
        log.debug("Fetching customer with id: {}", id);
        return customerRepository.findById(id)
                .map(this::toDTO);
    }

    /**
     * Creates a new customer from DTO.
     *
     * @param dto the customer DTO to create
     * @return the saved customer as DTO
     */
    @Transactional
    public CustomerDTO createCustomer(@NonNull CustomerDTO dto) {
        log.debug("Creating new customer: {}", dto);
        AbstractCustomer customer = toEntity(dto);
        AbstractCustomer saved = customerRepository.save(customer);
        return toDTO(saved);
    }

    /**
     * Updates an existing customer from DTO.
     *
     * @param id  the customer ID
     * @param dto the updated customer DTO
     * @return optional containing the updated customer DTO if found
     */
    @Transactional
    public Optional<CustomerDTO> updateCustomer(@NonNull Long id, @NonNull CustomerDTO dto) {
        log.debug("Updating customer with id: {}", id);
        return customerRepository.findById(id)
                .map(existing -> {
                    updateEntityFromDTO(existing, dto);
                    AbstractCustomer saved = customerRepository.save(existing);
                    return toDTO(saved);
                });
    }

    /**
     * Deletes a customer by ID.
     *
     * @param id the customer ID
     * @return true if customer was deleted, false if not found
     */
    @Transactional
    public boolean deleteCustomer(@NonNull Long id) {
        log.debug("Deleting customer with id: {}", id);
        if (customerRepository.existsById(id)) {
            customerRepository.deleteById(id);
            return true;
        }
        return false;
    }

    /**
     * Checks if a customer exists by ID.
     *
     * @param id the customer ID
     * @return true if customer exists
     */
    @Transactional(readOnly = true)
    public boolean existsById(@NonNull Long id) {
        return customerRepository.existsById(id);
    }

    /**
     * Converts an entity to DTO.
     *
     * @param entity the customer entity
     * @return the customer DTO
     */
    private CustomerDTO toDTO(AbstractCustomer entity) {
        String customerType = determineCustomerType(entity);
        return CustomerDTO.builder()
                .id(entity.getId())
                .customerNumber(entity.getCustomerNumber())
                .lastName(entity.getLastName())
                .firstName(entity.getFirstName())
                .phoneNumber(entity.getPhoneNumber())
                .email(entity.getEmail())
                .primaryAddress(entity.getPrimaryAddress())
                .vehicleVins(entity.getVehicleVins())
                .customerType(customerType)
                .build();
    }

    /**
     * Creates a new entity from DTO.
     *
     * @param dto the customer DTO
     * @return the customer entity
     */
    private AbstractCustomer toEntity(CustomerDTO dto) {
        AbstractCustomer entity = createEntityByType(dto.getCustomerType());
        entity.setCustomerNumber(dto.getCustomerNumber());
        entity.setLastName(dto.getLastName());
        entity.setFirstName(dto.getFirstName());
        entity.setPhoneNumber(dto.getPhoneNumber());
        entity.setEmail(dto.getEmail());
        entity.setPrimaryAddress(dto.getPrimaryAddress());
        if (dto.getVehicleVins() != null) {
            entity.getVehicleVins().addAll(dto.getVehicleVins());
        }
        return entity;
    }

    /**
     * Updates an existing entity from DTO.
     *
     * @param entity the existing entity to update
     * @param dto    the DTO with updated values
     */
    private void updateEntityFromDTO(AbstractCustomer entity, CustomerDTO dto) {
        if (dto.getCustomerNumber() != null) {
            entity.setCustomerNumber(dto.getCustomerNumber());
        }
        if (dto.getLastName() != null) {
            entity.setLastName(dto.getLastName());
        }
        if (dto.getFirstName() != null) {
            entity.setFirstName(dto.getFirstName());
        }
        if (dto.getPhoneNumber() != null) {
            entity.setPhoneNumber(dto.getPhoneNumber());
        }
        if (dto.getEmail() != null) {
            entity.setEmail(dto.getEmail());
        }
        if (dto.getPrimaryAddress() != null) {
            entity.setPrimaryAddress(dto.getPrimaryAddress());
        }
        if (dto.getVehicleVins() != null) {
            entity.getVehicleVins().clear();
            entity.getVehicleVins().addAll(dto.getVehicleVins());
        }
    }

    /**
     * Creates the appropriate entity subclass based on customer type.
     *
     * @param customerType the customer type string
     * @return the appropriate entity instance
     */
    private AbstractCustomer createEntityByType(String customerType) {
        if (customerType == null) {
            return new PrivateCustomer();
        }
        return switch (customerType.toLowerCase()) {
            case "commercial" -> new CommercialCustomer();
            case "government" -> new GovernmentCustomer();
            default -> new PrivateCustomer();
        };
    }

    /**
     * Determines the customer type from the entity class.
     *
     * @param entity the customer entity
     * @return the customer type string
     */
    private String determineCustomerType(AbstractCustomer entity) {
        if (entity instanceof CommercialCustomer) {
            return "commercial";
        } else if (entity instanceof GovernmentCustomer) {
            return "government";
        }
        return "private";
    }
}
