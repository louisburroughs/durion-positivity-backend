package com.positivity.nhtsa.internal.service;

import java.time.Clock;

import com.positivity.nhtsa.internal.entity.*;
import com.positivity.nhtsa.internal.exception.CarApiException;
import com.positivity.nhtsa.internal.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Service
public class VehicleReferenceService {
    private final Clock clock;

    private static final Duration CACHE_EXPIRY = Duration.ofHours(24);
    private static final String NHTSA_API_BASE = "https://vpic.nhtsa.dot.gov/api/vehicles";

    private final ManufacturerRepository manufacturerRepository;
    private final MakeRepository makeRepository;
    private final ModelRepository modelRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final RestClient restClient;
    private final VehicleVariableRepository vehicleVariableRepository;
    private final VehicleVariableValueRepository vehicleVariableValueRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<VehicleVariable> getVehicleVariables() {
        List<VehicleVariable> cached = vehicleVariableRepository.findAll();
        if (!cached.isEmpty() && !isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetVehicleVariableList?format=json";
        String response = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get("Results");
            vehicleVariableRepository.deleteAll();
            for (JsonNode node : results) {
                VehicleVariable variable = new VehicleVariable();
                variable.setName(node.path("Name").asString(""));
                variable.setDescription(node.path("Description").asString(""));
                variable.setCacheTimestamp(LocalDateTime.now(clock));
                vehicleVariableRepository.save(variable);
            }
        } catch (Exception e) {
            throw new CarApiException("Failed to parse vehicle variables", e);
        }
        return vehicleVariableRepository.findAll();
    }

    public List<VehicleVariableValue> getVehicleVariableValues(UUID variableId) {
        List<VehicleVariableValue> cached = vehicleVariableValueRepository.findByVariable_Id(variableId);
        if (!cached.isEmpty() && !isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetVehicleVariableValuesList/" + variableId + "?format=json";
        String response = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get("Results");
            vehicleVariableValueRepository.deleteAll(cached);
            for (JsonNode node : results) {
                VehicleVariableValue value = new VehicleVariableValue();
                value.setVariable(vehicleVariableRepository.getReferenceById(variableId));
                value.setValue(node.path("Value").asString(""));
                value.setValueId(node.path("ValueId").asString(""));
                value.setCacheTimestamp(LocalDateTime.now(clock));
                vehicleVariableValueRepository.save(value);
            }
        } catch (Exception e) {
            throw new CarApiException("Failed to parse vehicle variable values", e);
        }
        return vehicleVariableValueRepository.findByVariable_Id(variableId);
    }

    public List<Manufacturer> getManufacturers() {
        List<Manufacturer> cached = manufacturerRepository.findAll();
        if (!cached.isEmpty() && isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/getallmanufacturers?format=json";
        String response = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get("Results");
            manufacturerRepository.deleteAll();
            for (JsonNode node : results) {
                Manufacturer m = new Manufacturer();
                // Generate UUID from NHTSA ID for consistency
                long nhtsaId = node.path("Mfr_ID").asLong();
                m.setId(java.util.UUID.nameUUIDFromBytes(("manufacturer-" + nhtsaId).getBytes()));
                m.setName(node.path("Mfr_CommonName").asString(""));
                m.setCacheTimestamp(LocalDateTime.now(clock));
                manufacturerRepository.save(m);
            }
        } catch (Exception e) {
            throw new CarApiException("Failed to parse manufacturers", e);
        }
        return manufacturerRepository.findAll();
    }

    public List<Make> getMakesByManufacturer(UUID manufacturerId) {
        Manufacturer manufacturer = manufacturerRepository.findById(manufacturerId)
                .orElseThrow(() -> new IllegalArgumentException("Manufacturer not found with ID: " + manufacturerId));
        List<Make> cached = makeRepository.findByManufacturerId(manufacturerId);
        if (!cached.isEmpty() && isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetMakeForManufacturer/" + manufacturerId + "?format=json";
        String response = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get("Results");
            makeRepository.deleteAll(cached);
            for (JsonNode node : results) {
                Make make = new Make();
                // Generate UUID from NHTSA ID for consistency
                long nhtsaId = node.path("Make_ID").asLong();
                make.setId(java.util.UUID.nameUUIDFromBytes(("make-" + nhtsaId).getBytes()));
                make.setName(node.path("Make_Name").asString(""));
                make.setManufacturer(manufacturer);
                make.setCacheTimestamp(LocalDateTime.now(clock));
                makeRepository.save(make);
            }
        } catch (Exception e) {
            throw new CarApiException("Failed to parse makes", e);
        }
        return makeRepository.findByManufacturerId(manufacturerId);
    }

    public List<Model> getModelsByMake(UUID makeId) {
        Make make = makeRepository.findById(makeId)
                .orElseThrow(() -> new IllegalArgumentException("Make not found with ID: " + makeId));
        List<Model> cached = modelRepository.findByMakeId(makeId);
        if (!cached.isEmpty() && isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetModelsForMakeId/" + makeId + "?format=json";
        String response = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get("Results");
            modelRepository.deleteAll(cached);
            for (JsonNode node : results) {
                Model model = new Model();
                // Generate UUID from NHTSA ID for consistency
                long nhtsaId = node.path("Model_ID").asLong();
                model.setId(java.util.UUID.nameUUIDFromBytes(("model-" + nhtsaId).getBytes()));
                model.setName(node.path("Model_Name").asString(""));
                model.setMake(make);
                model.setCacheTimestamp(LocalDateTime.now(clock));
                modelRepository.save(model);
            }
        } catch (Exception e) {
            throw new CarApiException("Failed to parse models", e);
        }
        return modelRepository.findByMakeId(makeId);
    }

    public List<VehicleType> getVehicleTypesForMake(UUID makeId) {
        Make make = makeRepository.findById(makeId)
                .orElseThrow(() -> new IllegalArgumentException("Make not found with ID: " + makeId));
        List<VehicleType> cached = vehicleTypeRepository.findByMakeId(makeId);
        if (!cached.isEmpty() && !isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetVehicleTypesForMakeId/" + makeId + "?format=json";
        String response = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get("Results");
            vehicleTypeRepository.deleteAll(cached);
            for (JsonNode node : results) {
                VehicleType vt = new VehicleType();
                vt.setMake(make);
                vt.setVehicleTypeId(node.path("VehicleTypeId").asString(""));
                vt.setVehicleTypeName(node.path("VehicleTypeName").asString(""));
                vt.setCacheTimestamp(LocalDateTime.now(clock));
                vehicleTypeRepository.save(vt);
            }
        } catch (Exception e) {
            throw new CarApiException("Failed to parse vehicle types for make", e);
        }
        return vehicleTypeRepository.findByMakeId(makeId);
    }

    private boolean isCacheExpired(LocalDateTime cacheTimestamp) {
        return cacheTimestamp != null && !cacheTimestamp.plus(CACHE_EXPIRY).isBefore(LocalDateTime.now(clock));
    }
}
