package com.positivity.vehicle.internal.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.events.EmitEvent;
import com.positivity.shared.dto.CreateVehicleRequest;
import com.positivity.shared.dto.VehicleResponse;
import com.positivity.vehicle.service.VehicleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for vehicle CRUD operations - CAP:091 Story #105.
 */
@Slf4j    "onses(value = {@ApiR          VehicleResose response = vt vehicle by ID", descripin =@    @ApiResp ummary = "Get vehicle by VIN", description @         @ApiResponeresponseCode = "44", descripin = "Vehicle  not found", content = @Content)})ing("/vin/{vin}")esonseEntiyVehiceRespne etehicleByVin@Parameer(description = Vhicle I Reso@ApiResponse(responseCod  "200", description = "Vehicle updated uccessful@ApiResponse(responseCode = "404",    @ApResponse(responseCode = "400,ing("/{vehicleId}")tp
        @Parameterdscription = "Vehile update rqest", required = true) @RequestBody CreateVehicleReq{VehicleResponse response  ehicleervice.updaeehicle(vehicleId, re      } catch (IllegalArgumentEx        log.wr("Failed to updat vehicle {:{}",        return ResponseEntiy.notFound().b

on(summary = "Delete vehicle", descriptionses(value = {   @ApResponse(responseCode = "204,@ApiResponse(responseCode = "404", descripton = "Vehile not found")e@    public ResponseEntity<Void> deleteVehicle(
            @Parameter(description = "Vehicle UUID", required = true) @PathVariable UUID vehicleId) {

        try {
            vehicleService.deleteVehicle(vehicleId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("Failed to delete vehicle {}: {}", vehicleId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
