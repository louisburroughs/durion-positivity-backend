package com.positivity.shopmanager.internal.controller;

import com.positivity.shopmanager.internal.dto.PersonDTO;
import com.positivity.shopmanager.internal.dto.ServiceEntityDTO;
import com.positivity.shopmanager.service.ShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Shop API", description = "Endpoints for shop management, technicians, and services")
@RestController
@RequestMapping("/v1/shop-manager")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shopService;

    @Operation(
            summary = "Get technician's person details",
            description = "Retrieve the person details for a technician by technician ID.")
    @ApiResponse(responseCode = "200", description = "Person details returned successfully.")
    @ApiResponse(responseCode = "404", description = "Technician or person not found.")
    @GetMapping("/{locationId}/technicians/{personId}/person")
    @PreAuthorize("hasAuthority('shop:location:view')")
    public ResponseEntity<PersonDTO> getTechnicianPerson(
            @Parameter(description = "ID of the shop", example = "123e4567-e89b-12d3-a456-426614174000") @PathVariable
                    UUID locationId,
            @Parameter(description = "ID of the technician", example = "123e4567-e89b-12d3-a456-426614174001")
                    @PathVariable
                    UUID personId) {
        PersonDTO person = shopService.getTechnicianPerson(locationId, personId);
        if (person == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(person);
    }

    @Operation(
            summary = "Get shop service details",
            description = "Retrieve the details of a shop service by service ID.")
    @ApiResponse(responseCode = "200", description = "Service details returned successfully.")
    @ApiResponse(responseCode = "404", description = "Service not found.")
    @GetMapping("/{locationId}/services/{serviceId}/details")
    @PreAuthorize("hasAuthority('shop:location:view')")
    public ResponseEntity<ServiceEntityDTO> getShopServiceDetails(
            @Parameter(description = "ID of the shop", example = "1") @PathVariable UUID locationId,
            @Parameter(description = "ID of the service", example = "1") @PathVariable UUID serviceId) {
        ServiceEntityDTO service = shopService.getShopServiceDetails(locationId, serviceId);
        if (service == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service);
    }
}
