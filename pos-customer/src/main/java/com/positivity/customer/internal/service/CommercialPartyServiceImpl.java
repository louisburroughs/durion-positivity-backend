package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.CustomerDTO;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
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
public class CommercialPartyServiceImpl implements CustomerService {

    private final CommercialPartyRepository commercialRepository;

    /**
     * Retrieves all customers as DTOs.
     *
     * @return list of all customers as DTOs
     */
    @Transactional(readOnly = true)
    public List<CustomerDTO> getAllCustomers() {
        log.debug("Fetching all customers");
        return commercialRepository.findAll().stream().map(this::toDTO).toList();
    }

    /**
     * Retrieves all commercial customers as DTOs with paging.
     *
     * @param pageable pagination information
     * @return page of commercial customers as DTOs
     */
    @Transactional(readOnly = true)
    public Page<CustomerDTO> getAllCustomers(@NonNull Pageable pageable) {
        log.debug("Fetching all person customers with paging: {}", pageable);
        Page<CommercialParty> page = commercialRepository.findAll(pageable);
        List<CustomerDTO> dtos = page.getContent().stream().map(this::toDTO).toList();
        return new PageImpl<>(dtos, pageable, page.getTotalElements());
    }

    /**
     * Retrieves all commercial customers as DTOs with paging.
     *
     * @param pageable pagination information
     * @return page of commercial customers as DTOs
     */
    @Transactional(readOnly = true)
    public Page<CustomerDTO> getAllCommercialCustomers(@NonNull Pageable pageable) {
        log.debug("Fetching all commercial customers with paging: {}", pageable);
        Page<CommercialParty> page = commercialRepository.findAll(pageable);
        List<CustomerDTO> dtos = page.getContent().stream().map(this::toDTO).toList();
        return new PageImpl<>(dtos, pageable, page.getTotalElements());
    }

    /**
     * Retrieves all customers as DTOs.
     *
     * @return list of all customers as DTOs
     */
    @Transactional(readOnly = true)
    public List<CustomerDTO> getAllCommercialCustomers() {
        log.debug("Fetching all commercial customers");
        return commercialRepository.findAll().stream().map(this::toDTO).toList();
    }

    /**
     * Retrieves a customer by ID as DTO.
     *
     * @param id the customer ID
     * @return optional containing the customer DTO if found
     */
    @Transactional(readOnly = true)
    public Optional<CustomerDTO> getCustomerById(@NonNull UUID id) {
        log.debug("Fetching customer with id: {}", id);
        // Try commercial repository
        return commercialRepository.findById(id).map(this::toDTO);
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
        CommercialParty customer = toEntity(dto);

        // Determine party type and save to appropriate repository
        PartyType partyType = customer.getPartyType();
        if (partyType == PartyType.COMMERCIAL) {
            return toDTO(commercialRepository.save(customer));
        }

        return null; // This service only handles commercial parties, return null or throw exception
        // for unsupported types
    }

    /**
     * Updates an existing customer from DTO.
     *
     * @param id  the customer ID
     * @param dto the updated customer DTO
     * @return optional containing the updated customer DTO if found
     */
    @Transactional
    public Optional<CustomerDTO> updateCustomer(@NonNull UUID id, @NonNull CustomerDTO dto) {
        log.debug("Updating customer with id: {}", id);

        // Try commercial repository
        Optional<CommercialParty> commercialOpt = commercialRepository.findById(id);
        if (commercialOpt.isPresent()) {
            CommercialParty existing = commercialOpt.get();
            updateEntityFromDTO(existing, dto);
            CommercialParty saved = commercialRepository.save(existing);
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
    @Transactional
    public boolean deleteCustomer(@NonNull UUID id) {
        log.debug("Deleting customer with id: {}", id);

        // Check commercial repository
        if (commercialRepository.existsById(id)) {
            commercialRepository.deleteById(id);
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
    public boolean existsById(@NonNull UUID id) {
        return commercialRepository.existsById(id);
    }

    /**
     * Converts an entity to DTO.
     *
     * @param entity the customer entity
     * @return the customer DTO
     */
    private CustomerDTO toDTO(CommercialParty entity) {
        PartyType customerType = determineCustomerType(entity);
        return CustomerDTO.builder()
                .id(entity.getPartyId())
                .customerNumber(entity.getCustomerNumber())
                .lastName(entity.getLegalName())
                .firstName(entity.getDisplayName())
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
    private CommercialParty toEntity(CustomerDTO dto) {
        CommercialParty entity = createEntityByType(dto.getCustomerType());
        entity.setCustomerNumber(dto.getCustomerNumber());
        entity.setLegalName(dto.getFirstName());
        entity.setDisplayName(dto.getLastName());
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
    private void updateEntityFromDTO(CommercialParty entity, CustomerDTO dto) {
        if (dto.getCustomerNumber() != null) {
            entity.setCustomerNumber(dto.getCustomerNumber());
        }
        if (dto.getFirstName() != null) {
            entity.setLegalName(dto.getFirstName());
        }
        if (dto.getLastName() != null) {
            entity.setDisplayName(dto.getLastName());
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
    private CommercialParty createEntityByType(String customerType) {
        if (customerType == null || customerType.isEmpty()) {
            log.debug("No customer type specified, defaulting to COMMERCIAL");
            return new CommercialParty();
        }

        try {
            PartyType type = PartyType.valueOf(customerType.toUpperCase());
            if (type == PartyType.COMMERCIAL) {
                return new CommercialParty();
            } else {
                log.warn("Unhandled customer type '{}', defaulting to COMMERCIAL", customerType);
                return new CommercialParty();
            }
        } catch (IllegalArgumentException e) {
            log.warn("Unknown customer type '{}', defaulting to COMMERCIAL", customerType);
            return new CommercialParty();
        }
    }

    /**
     * Determines the customer type from the entity class.
     *
     * @param entity the customer entity
     * @return the customer type string
     */
    private PartyType determineCustomerType(CommercialParty entity) {
        return entity.getPartyType();
    }
}
