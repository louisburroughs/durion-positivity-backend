package com.positivity.vehiclefitment.internal.service;

import com.positivity.vehiclefitment.internal.dto.CreatePartFitmentRequest;
import com.positivity.vehiclefitment.internal.dto.PartFitmentResponse;
import com.positivity.vehiclefitment.internal.entity.Make;
import com.positivity.vehiclefitment.internal.entity.Manufacturer;
import com.positivity.vehiclefitment.internal.entity.Model;
import com.positivity.vehiclefitment.internal.entity.PartFitmentEntity;
import com.positivity.vehiclefitment.internal.entity.VehicleType;
import com.positivity.vehiclefitment.internal.entity.VehicleVariable;
import com.positivity.vehiclefitment.internal.entity.VehicleVariableValue;
import com.positivity.vehiclefitment.internal.exception.VehicleFitmentException;
import com.positivity.vehiclefitment.internal.repository.MakeRepository;
import com.positivity.vehiclefitment.internal.repository.ManufacturerRepository;
import com.positivity.vehiclefitment.internal.repository.ModelRepository;
import com.positivity.vehiclefitment.internal.repository.PartFitmentRepository;
import com.positivity.vehiclefitment.internal.repository.VehicleTypeRepository;
import com.positivity.vehiclefitment.internal.repository.VehicleVariableRepository;
import com.positivity.vehiclefitment.internal.repository.VehicleVariableValueRepository;
import com.positivity.vehiclefitment.service.VehicleFitmentService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@RequiredArgsConstructor
@Service
public class VehicleFitmentServiceImpl implements VehicleFitmentService {
    private final Clock clock;

    private static final Duration CACHE_EXPIRY = Duration.ofHours(24);
    private static final String NHTSA_API_BASE = "https://vpic.nhtsa.dot.gov/v1/vehicles";
    private final ManufacturerRepository manufacturerRepository;
    private final MakeRepository makeRepository;
    private final ModelRepository modelRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final PartFitmentRepository partFitmentRepository;
    private final RestClient restClient;
    private final VehicleVariableRepository vehicleVariableRepository;
    private final VehicleVariableValueRepository vehicleVariableValueRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public @NonNull PartFitmentResponse createFitment(@NonNull CreatePartFitmentRequest request) {
        Manufacturer manufacturer = null;
        Make make = null;
        Model model = null;
        VehicleType vehicleType = null;

        if (StringUtils.hasText(request.getManufacturerName())) {
            manufacturer = manufacturerRepository
                    .findByNameIgnoreCase(request.getManufacturerName())
                    .orElseGet(() -> {
                        Manufacturer newManufacturer = new Manufacturer();
                        newManufacturer.setName(request.getManufacturerName());
                        return manufacturerRepository.save(newManufacturer);
                    });
        }

        if (StringUtils.hasText(request.getMakeName())) {
            final Manufacturer resolvedManufacturer = manufacturer;
            make = makeRepository.findByNameIgnoreCase(request.getMakeName()).orElseGet(() -> {
                Make newMake = new Make();
                newMake.setName(request.getMakeName());
                newMake.setManufacturer(resolvedManufacturer);
                return makeRepository.save(newMake);
            });
        }

        if (StringUtils.hasText(request.getModelName())) {
            final Make resolvedMake = make;
            model = modelRepository.findByNameIgnoreCase(request.getModelName()).orElseGet(() -> {
                Model newModel = new Model();
                newModel.setName(request.getModelName());
                newModel.setMake(resolvedMake);
                return modelRepository.save(newModel);
            });
        }

        if (StringUtils.hasText(request.getVehicleTypeName())) {
            final Make resolvedMake = make;
            vehicleType = vehicleTypeRepository
                    .findByVehicleTypeNameIgnoreCase(request.getVehicleTypeName())
                    .orElseGet(() -> {
                        VehicleType newVehicleType = new VehicleType();
                        newVehicleType.setVehicleTypeName(request.getVehicleTypeName());
                        newVehicleType.setMake(resolvedMake);
                        return vehicleTypeRepository.save(newVehicleType);
                    });
        }

        PartFitmentEntity entity = new PartFitmentEntity();
        entity.setPartNumberId(request.getPartNumberId());
        entity.setVehicleManufacturer(manufacturer);
        entity.setVehicleMake(make);
        entity.setVehicleModel(model);
        entity.setVehicleType(vehicleType);
        entity.setVehicleYear(request.getVehicleYear());
        entity.setEngineType(request.getEngineType());
        entity.setSubmodel(request.getSubmodel());
        entity.setNotes(request.getNotes());

        PartFitmentEntity saved = partFitmentRepository.save(entity);

        return PartFitmentResponse.builder()
                .id(saved.getId())
                .partNumberId(saved.getPartNumberId())
                .manufacturerName(manufacturer != null ? manufacturer.getName() : null)
                .makeName(make != null ? make.getName() : null)
                .modelName(model != null ? model.getName() : null)
                .vehicleTypeName(vehicleType != null ? vehicleType.getVehicleTypeName() : null)
                .vehicleYear(saved.getVehicleYear())
                .engineType(saved.getEngineType())
                .submodel(saved.getSubmodel())
                .notes(saved.getNotes())
                .build();
    }

    @Override
    public List<VehicleVariable> getVehicleVariables() {
        List<VehicleVariable> cached = vehicleVariableRepository.findAll();
        if (!cached.isEmpty() && !isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetVehicleVariableList?format=json";
        String response = restClient.get().uri(url).retrieve().body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get(RESULTS);
            vehicleVariableRepository.deleteAll();
            for (JsonNode node : results) {
                VehicleVariable variable = new VehicleVariable();
                variable.setName(node.path("Name").asString(""));
                variable.setDescription(node.path("Description").asString(""));
                variable.setCacheTimestamp(LocalDateTime.now(clock));
                vehicleVariableRepository.save(variable);
            }
        } catch (Exception e) {
            throw new VehicleFitmentException("Failed to parse vehicle variables", e);
        }
        return vehicleVariableRepository.findAll();
    }

    @Override
    public List<VehicleVariableValue> getVehicleVariableValues(UUID variableId) {
        List<VehicleVariableValue> cached = vehicleVariableValueRepository.findByVariable_Id(variableId);
        if (!cached.isEmpty() && !isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetVehicleVariableValuesList/" + variableId + FORMAT_JSON;
        String response = restClient.get().uri(url).retrieve().body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get(RESULTS);
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
            throw new VehicleFitmentException("Failed to parse vehicle variable values", e);
        }
        return vehicleVariableValueRepository.findByVariable_Id(variableId);
    }

    @Override
    public List<Manufacturer> getManufacturers() {
        List<Manufacturer> cached = manufacturerRepository.findAll();
        if (!cached.isEmpty() && isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/getallmanufacturers?format=json";
        String response = restClient.get().uri(url).retrieve().body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get(RESULTS);
            manufacturerRepository.deleteAll();
            for (JsonNode node : results) {
                Manufacturer m = new Manufacturer();
                // Generate UUID from NHTSA ID for consistency
                UUID nhtsaId = UUID.fromString(node.path("Mfr_ID").asString());
                m.setId(java.util.UUID.nameUUIDFromBytes(("manufacturer-" + nhtsaId).getBytes()));
                m.setName(node.path("Mfr_CommonName").asString(""));
                m.setCacheTimestamp(LocalDateTime.now(clock));
                manufacturerRepository.save(m);
            }
        } catch (Exception e) {
            throw new VehicleFitmentException("Failed to parse manufacturers", e);
        }
        return manufacturerRepository.findAll();
    }

    @Override
    public List<Make> getMakesByManufacturer(UUID manufacturerId) {
        Manufacturer manufacturer = manufacturerRepository
                .findById(manufacturerId)
                .orElseThrow(() -> new IllegalArgumentException("Manufacturer not found with ID: " + manufacturerId));
        List<Make> cached = makeRepository.findByManufacturerId(manufacturerId);
        if (!cached.isEmpty() && isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetMakeForManufacturer/" + manufacturerId + FORMAT_JSON;
        String response = restClient.get().uri(url).retrieve().body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get(RESULTS);
            makeRepository.deleteAll(cached);
            for (JsonNode node : results) {
                Make make = new Make();
                // Generate UUID from NHTSA ID for consistency
                UUID nhtsaId = UUID.fromString(node.path("Make_ID").asString());
                make.setId(java.util.UUID.nameUUIDFromBytes(("make-" + nhtsaId).getBytes()));
                make.setName(node.path("Make_Name").asString(""));
                make.setManufacturer(manufacturer);
                make.setCacheTimestamp(LocalDateTime.now(clock));
                makeRepository.save(make);
            }
        } catch (Exception e) {
            throw new VehicleFitmentException("Failed to parse makes", e);
        }
        return makeRepository.findByManufacturerId(manufacturerId);
    }

    @Override
    public List<Model> getModelsByMake(UUID makeId) {
        Make make = makeRepository
                .findById(makeId)
                .orElseThrow(() -> new IllegalArgumentException("Make not found with ID: " + makeId));
        List<Model> cached = modelRepository.findByMakeId(makeId);
        if (!cached.isEmpty() && isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetModelsForMakeId/" + makeId + FORMAT_JSON;
        String response = restClient.get().uri(url).retrieve().body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get(RESULTS);
            modelRepository.deleteAll(cached);
            for (JsonNode node : results) {
                Model model = new Model();
                // Generate UUID from NHTSA ID for consistency
                UUID nhtsaId = UUID.fromString(node.path("Model_ID").asString());
                model.setId(java.util.UUID.nameUUIDFromBytes(("model-" + nhtsaId).getBytes()));
                model.setName(node.path("Model_Name").asString(""));
                model.setMake(make);
                model.setCacheTimestamp(LocalDateTime.now(clock));
                modelRepository.save(model);
            }
        } catch (Exception e) {
            throw new VehicleFitmentException("Failed to parse models", e);
        }
        return modelRepository.findByMakeId(makeId);
    }

    @Override
    public List<VehicleType> getVehicleTypesForMake(UUID makeId) {
        Make make = makeRepository
                .findById(makeId)
                .orElseThrow(() -> new IllegalArgumentException("Make not found with ID: " + makeId));
        List<VehicleType> cached = vehicleTypeRepository.findByMakeId(makeId);
        if (!cached.isEmpty() && !isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetVehicleTypesForMakeId/" + makeId + FORMAT_JSON;
        String response = restClient.get().uri(url).retrieve().body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get(RESULTS);
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
            throw new VehicleFitmentException("Failed to parse vehicle types for make", e);
        }
        return vehicleTypeRepository.findByMakeId(makeId);
    }

    private boolean isCacheExpired(LocalDateTime cacheTimestamp) {
        return cacheTimestamp != null && !cacheTimestamp.plus(CACHE_EXPIRY).isBefore(LocalDateTime.now(clock));
    }
}
