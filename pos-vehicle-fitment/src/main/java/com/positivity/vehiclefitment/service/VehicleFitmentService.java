package com.positivity.vehiclefitment.service;

import com.positivity.vehiclefitment.entity.*;
import com.positivity.vehiclefitment.repository.*;
import com.positivity.vehiclefitment.exception.VehicleFitmentException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Service
public class VehicleFitmentService {
    private static final Duration CACHE_EXPIRY = Duration.ofHours(24);
    private static final String NHTSA_API_BASE = "https://vpic.nhtsa.dot.gov/api/vehicles";
    public static final String RESULTS = "Results";
    public static final String FORMAT_JSON = "?format=json";

    private final ManufacturerRepository manufacturerRepository;
    private final MakeRepository makeRepository;
    private final ModelRepository modelRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final RestTemplate restTemplate;
    private final VehicleVariableRepository vehicleVariableRepository;
    private final VehicleVariableValueRepository vehicleVariableValueRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<VehicleVariable> getVehicleVariables() {
        List<VehicleVariable> cached = vehicleVariableRepository.findAll();
        if (!cached.isEmpty() && !isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetVehicleVariableList?format=json";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode results = root.get(RESULTS);
            vehicleVariableRepository.deleteAll();
            for (JsonNode node : results) {
                VehicleVariable variable = new VehicleVariable();
                variable.setName(node.path("Name").asText(""));
                variable.setDescription(node.path("Description").asText(""));
                variable.setCacheTimestamp(LocalDateTime.now());
                vehicleVariableRepository.save(variable);
            }
        } catch (Exception e) {
            throw new VehicleFitmentException("Failed to parse vehicle variables", e);
        }
        return vehicleVariableRepository.findAll();
    }

    public List<VehicleVariableValue> getVehicleVariableValues(Long variableId) {
        List<VehicleVariableValue> cached = vehicleVariableValueRepository.findByVariableId(variableId);
        if (!cached.isEmpty() && !isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetVehicleVariableValuesList/" + variableId + FORMAT_JSON;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode results = root.get(RESULTS);
            vehicleVariableValueRepository.deleteAll(cached);
            for (JsonNode node : results) {
                VehicleVariableValue value = new VehicleVariableValue();
                value.setVariableId(variableId);
                value.setValue(node.path("Value").asText(""));
                value.setValueId(node.path("ValueId").asText(""));
                value.setCacheTimestamp(LocalDateTime.now());
                vehicleVariableValueRepository.save(value);
            }
        } catch (Exception e) {
            throw new VehicleFitmentException("Failed to parse vehicle variable values", e);
        }
        return vehicleVariableValueRepository.findByVariableId(variableId);
    }

    public List<Manufacturer> getManufacturers() {
        List<Manufacturer> cached = manufacturerRepository.findAll();
        if (!cached.isEmpty() && isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/getallmanufacturers?format=json";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode results = root.get(RESULTS);
            manufacturerRepository.deleteAll();
            for (JsonNode node : results) {
                Manufacturer m = new Manufacturer();
                m.setId(node.path("Mfr_ID").asLong());
                m.setName(node.path("Mfr_CommonName").asText(""));
                m.setCacheTimestamp(LocalDateTime.now());
                manufacturerRepository.save(m);
            }
        } catch (Exception e) {
            throw new VehicleFitmentException("Failed to parse manufacturers", e);
        }
        return manufacturerRepository.findAll();
    }

    public List<Make> getMakesByManufacturer(Long manufacturerId) {
        Manufacturer manufacturer = manufacturerRepository.findById(manufacturerId)
                .orElseThrow(() -> new IllegalArgumentException("Manufacturer not found with ID: " + manufacturerId));
        List<Make> cached = makeRepository.findByManufacturerId(manufacturerId);
        if (!cached.isEmpty() && isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetMakeForManufacturer/" + manufacturerId + FORMAT_JSON;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode results = root.get(RESULTS);
            makeRepository.deleteAll(cached);
            for (JsonNode node : results) {
                Make make = new Make();
                make.setId(node.path("Make_ID").asLong());
                make.setName(node.path("Make_Name").asText(""));
                make.setManufacturer(manufacturer);
                make.setCacheTimestamp(LocalDateTime.now());
                makeRepository.save(make);
            }
        } catch (Exception e) {
            throw new VehicleFitmentException("Failed to parse makes", e);
        }
        return makeRepository.findByManufacturerId(manufacturerId);
    }

    public List<Model> getModelsByMake(Long makeId) {
        Make make = makeRepository.findById(makeId)
                .orElseThrow(() -> new IllegalArgumentException("Make not found with ID: " + makeId));
        List<Model> cached = modelRepository.findByMakeId(makeId);
        if (!cached.isEmpty() && isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetModelsForMakeId/" + makeId + FORMAT_JSON;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode results = root.get(RESULTS);
            modelRepository.deleteAll(cached);
            for (JsonNode node : results) {
                Model model = new Model();
                model.setId(node.path("Model_ID").asLong());
                model.setName(node.path("Model_Name").asText(""));
                model.setMake(make);
                model.setCacheTimestamp(LocalDateTime.now());
                modelRepository.save(model);
            }
        } catch (Exception e) {
            throw new VehicleFitmentException("Failed to parse models", e);
        }
        return modelRepository.findByMakeId(makeId);
    }

    public List<VehicleType> getVehicleTypesForMake(Long makeId) {
        Make make = makeRepository.findById(makeId)
                .orElseThrow(() -> new IllegalArgumentException("Make not found with ID: " + makeId));
        List<VehicleType> cached = vehicleTypeRepository.findByMakeId(makeId);
        if (!cached.isEmpty() && !isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetVehicleTypesForMakeId/" + makeId + FORMAT_JSON;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode results = root.get(RESULTS);
            vehicleTypeRepository.deleteAll(cached);
            for (JsonNode node : results) {
                VehicleType vt = new VehicleType();
                vt.setMake(make);
                vt.setVehicleTypeId(node.path("VehicleTypeId").asText(""));
                vt.setVehicleTypeName(node.path("VehicleTypeName").asText(""));
                vt.setCacheTimestamp(LocalDateTime.now());
                vehicleTypeRepository.save(vt);
            }
        } catch (Exception e) {
            throw new VehicleFitmentException("Failed to parse vehicle types for make", e);
        }
        return vehicleTypeRepository.findByMakeId(makeId);
    }

    private boolean isCacheExpired(LocalDateTime cacheTimestamp) {
        return cacheTimestamp != null && !cacheTimestamp.plus(CACHE_EXPIRY).isBefore(LocalDateTime.now());
    }
}
