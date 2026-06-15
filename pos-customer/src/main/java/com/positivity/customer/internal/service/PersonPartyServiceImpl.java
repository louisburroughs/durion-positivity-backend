package com.positivity.customer.internal.service;

import com.positivity.customer.internal.client.PeopleClient;
import com.positivity.customer.internal.dto.CustomerDTO;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.service.CustomerService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing Customer entities.
 * Provides business logic layer between controllers and repositories.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonPartyServiceImpl implements CustomerService {

    private final PersonPartyRepository customerRepository;
    private final PeopleClient peopleClient;

    /**
     * Retrieves all customers as DTOs.
     *
     * @return list of all customers as DTOs
     */
    @Override
    @Transactional(readOnly = true)
    public List<CustomerDTO> getAllCustomers() {
        log.debug("Fetching all customers");
        return customerRepository.findAll().stream().map(this::toDTO).toList();
    }

    /**
     * Retrieves all person customers as DTOs with paging.
     *
     * @param pageable pagination information
     * @return page of person customers as DTOs
     */
    @Override
    @Transactional(readOnly = true)
    public Page<CustomerDTO> getAllCustomers(@NonNull Pageable pageable) {
        log.debug("Fetching all person customers with paging: {}", pageable);
        Page<PersonParty> page = customerRepository.findAll(pageable);
        List<CustomerDTO> dtos = page.getContent().stream().map(this::toDTO).toList();
        return new PageImpl<>(dtos, pageable, page.getTotalElements());
    }

    /**
     * Retrieves a customer by ID as DTO.
     *
     * @param id the customer ID
     * @return optional containing the customer DTO if found
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerDTO> getCustomerById(@NonNull UUID id) {
        log.debug("Fetching customer with id: {}", id);

        // Try person repository first
        Optional<PersonParty> personOpt = customerRepository.findById(id);
        if (personOpt.isPresent()) {
            return personOpt.map(this::toDTO);
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public Optional<PersonParty> findPartyById(@NonNull UUID id) {
        return customerRepository.findById(id);
    }

    /**
     * Creates a new customer from DTO.
     *
     * @param dto the customer DTO to create
     * @return the saved customer as DTO
     */
    @Override
    @Transactional
    public CustomerDTO createCustomer(@NonNull CustomerDTO dto) {
        log.debug("Creating new customer: {}", dto);
        PersonParty customer = toEntity(dto);

        // Determine party type and save to appropriate repository
        PersonParty saved = customerRepository.save(customer);

        return toDTO(saved);
    }

    /**
     * Updates an existing customer from DTO.
     *
     * @param id  the customer ID
     * @param dto the updated customer DTO
     * @return optional containing the updated customer DTO if found
     */
    @Override
    @Transactional
    public Optional<CustomerDTO> updateCustomer(@NonNull UUID id, @NonNull CustomerDTO dto) {
        log.debug("Updating customer with id: {}", id);

        // Try to find in person repository first
        Optional<PersonParty> personOpt = customerRepository.findById(id);
        if (personOpt.isPresent()) {
            PersonParty existing = personOpt.get();
            updateEntityFromDTO(existing, dto);
            PersonParty saved = customerRepository.save(existing);
            return Optional.of(toDTO(saved));
        }

        return Optional.empty();
    }

    /**
     * Deletes a customer by ID.
     *
     * @param id the customer ID
     * @return true if customer was deleted, false if not found
     */
    @Override
    @Transactional
    public boolean deleteCustomer(@NonNull UUID id) {
        log.debug("Deleting customer with id: {}", id);

        // Check person repository first
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
    @Override
    @Transactional(readOnly = true)
    public boolean existsById(@NonNull UUID id) {
        return customerRepository.existsById(id);
    }

    /**
     * Converts an entity to DTO.
     *
     * @param entity the customer entity
     * @return the customer DTO
     */
    private CustomerDTO toDTO(PersonParty entity) {
        PartyType customerType = determineCustomerType(entity);
        return CustomerDTO.builder()
                .id(entity.getPartyId())
                .customerNumber(entity.getCustomerNumber())
                .lastName(entity.getLastName())
                .firstName(entity.getFirstName())
                .phoneNumber(entity.getPhoneNumber())
                .email(entity.getEmail())
                .primaryAddress(entity.getPrimaryAddress())
                .vehicleVins(new ArrayList<>(entity.getVehicleVins()))
                .customerType(customerType.toString())
                .build();
    }

    /**
     * Creates a new entity from DTO.
     *
     * @param dto the customer DTO
     * @return the customer entity
     */
    private PersonParty toEntity(CustomerDTO dto) {
        PersonParty entity = createEntityByType(dto.getCustomerType());
        UUID canonicalPersonId = peopleClient.resolveOrCreatePersonId(
                dto.getEmail(), dto.getPhoneNumber(), dto.getLastName(), dto.getFirstName());
        entity.setPersonId(canonicalPersonId);
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
    private void updateEntityFromDTO(PersonParty entity, CustomerDTO dto) {
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
     * Creates a new customer entity based on the specified type.
     *
     * @param customerType the type of customer to create (e.g., "PERSON",
     *                     "COMMERCIAL")
     * @return a new customer instance of the appropriate type
     */
    private PersonParty createEntityByType(String customerType) {
        if (customerType == null || customerType.isEmpty()) {
            log.debug("No customer type specified, defaulting to PERSON");
            return new PersonParty();
        }

        try {
            PartyType type = PartyType.valueOf(customerType.toUpperCase());
            if (type == PartyType.PERSON) {
                return new PersonParty();
            } else {
                log.warn("Unhandled customer type '{}', defaulting to PERSON", customerType);
                return new PersonParty();
            }
        } catch (IllegalArgumentException e) {
            log.warn("Unknown customer type '{}', defaulting to PERSON", customerType);
            return new PersonParty();
        }
    }

    /**
     * Determines the customer type from the entity class.
     *
     * @param entity the customer entity
     * @return the customer type string
     */
    private PartyType determineCustomerType(PersonParty entity) {
        return entity.getPartyType();
    }
}
