package com.positivity.vehiclefitment.internal.service;

import com.positivity.vehiclefitment.internal.dto.MakeResponse;
import com.positivity.vehiclefitment.internal.dto.ManufacturerResponse;
import com.positivity.vehiclefitment.internal.dto.ModelResponse;
import com.positivity.vehiclefitment.internal.dto.VehicleFitmentMapper;
import com.positivity.vehiclefitment.internal.dto.VehicleTypeResponse;
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
import com.positivity.vehiclefitment.internal.service.dto.CreatePartFitmentRequest;
import com.positivity.vehiclefitment.internal.service.dto.PartFitmentResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
    /**
     * vPIC API base. Must be {@code /api/vehicles} — this previously read
     * {@code /v1/vehicles}, which is not a vPIC path: it redirects to vPIC's NotFound page
     * (#2395). Same base as {@code pos-vehicle-reference-nhtsa}; vPIC's own example is
     * {@code https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVin/5UXWX7C5*BA?format=xml&modelyear=2011}.
     */
    private static final String NHTSA_API_BASE = "https://vpic.nhtsa.dot.gov/api/vehicles";

    private final ManufacturerRepository manufacturerRepository;
    private final MakeRepository makeRepository;
    private final ModelRepository modelRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final PartFitmentRepository partFitmentRepository;
    private final RestClient restClient;
    private final VehicleVariableRepository vehicleVariableRepository;
    private final VehicleVariableValueRepository vehicleVariableValueRepository;
    /** Writes a refresh in one transaction, opened only after the vPIC call has returned and parsed. */
    private final TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public @NonNull PartFitmentResponse createFitment(@NonNull CreatePartFitmentRequest request) {
        Manufacturer manufacturer = resolveManufacturer(request.getManufacturerName());
        Make make = resolveMake(request.getMakeName(), manufacturer);
        Model model = resolveModel(request.getModelName(), make);
        VehicleType vehicleType = resolveVehicleType(request.getVehicleTypeName(), make);

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

    private Manufacturer resolveManufacturer(String name) {
        String normalized = name != null ? name.trim() : null;
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        List<Manufacturer> existing = manufacturerRepository.findAllByNameIgnoreCase(normalized);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        Manufacturer entity = new Manufacturer();
        entity.setName(normalized);
        try {
            return manufacturerRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException _) {
            return manufacturerRepository.findAllByNameIgnoreCase(normalized).stream()
                    .findFirst()
                    .orElseThrow(
                            () -> new VehicleFitmentException("Concurrent insert race on manufacturer: " + normalized));
        }
    }

    private Make resolveMake(String name, Manufacturer manufacturer) {
        String normalized = name != null ? name.trim() : null;
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        Optional<Make> found = manufacturer != null
                ? makeRepository.findByManufacturerIdAndNameIgnoreCase(manufacturer.getId(), normalized)
                : makeRepository.findByManufacturerIsNullAndNameIgnoreCase(normalized);
        if (found.isPresent()) {
            return found.get();
        }
        Make entity = new Make();
        entity.setName(normalized);
        entity.setManufacturer(manufacturer);
        try {
            return makeRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException _) {
            return (manufacturer != null
                            ? makeRepository.findByManufacturerIdAndNameIgnoreCase(manufacturer.getId(), normalized)
                            : makeRepository.findByManufacturerIsNullAndNameIgnoreCase(normalized))
                    .orElseThrow(() -> new VehicleFitmentException("Concurrent insert race on make: " + normalized));
        }
    }

    private Model resolveModel(String name, Make make) {
        String normalized = name != null ? name.trim() : null;
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        Optional<Model> found = make != null
                ? modelRepository.findByMakeIdAndNameIgnoreCase(make.getId(), normalized)
                : modelRepository.findByMakeIsNullAndNameIgnoreCase(normalized);
        if (found.isPresent()) {
            return found.get();
        }
        Model entity = new Model();
        entity.setName(normalized);
        entity.setMake(make);
        try {
            return modelRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException _) {
            return (make != null
                            ? modelRepository.findByMakeIdAndNameIgnoreCase(make.getId(), normalized)
                            : modelRepository.findByMakeIsNullAndNameIgnoreCase(normalized))
                    .orElseThrow(() -> new VehicleFitmentException("Concurrent insert race on model: " + normalized));
        }
    }

    private VehicleType resolveVehicleType(String name, Make make) {
        String normalized = name != null ? name.trim() : null;
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        Optional<VehicleType> found = make != null
                ? vehicleTypeRepository.findByMakeIdAndVehicleTypeNameIgnoreCase(make.getId(), normalized)
                : vehicleTypeRepository.findByMakeIsNullAndVehicleTypeNameIgnoreCase(normalized);
        if (found.isPresent()) {
            return found.get();
        }
        VehicleType entity = new VehicleType();
        entity.setVehicleTypeName(normalized);
        entity.setMake(make);
        try {
            return vehicleTypeRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException _) {
            return (make != null
                            ? vehicleTypeRepository.findByMakeIdAndVehicleTypeNameIgnoreCase(make.getId(), normalized)
                            : vehicleTypeRepository.findByMakeIsNullAndVehicleTypeNameIgnoreCase(normalized))
                    .orElseThrow(
                            () -> new VehicleFitmentException("Concurrent insert race on vehicleType: " + normalized));
        }
    }

    /*
     * Variables and their values refresh from vPIC in place (#2454), like manufacturers and makes (#2416): a
     * variable is keyed by vPIC's own id ({@code ID}) and a value by (variable, vPIC {@code Id}), nothing is
     * deleted, and the whole payload is validated before the one transaction that writes it. part_fitment_entity
     * references values by foreign key, so the delete and reinsert this replaced failed once a fitment used one.
     */
    @Override
    public List<VehicleVariable> getVehicleVariables() {
        List<VehicleVariable> cached = vehicleVariableRepository.findAll();
        if (isCacheFresh(lastRefreshed(cached, VehicleVariable::getCacheTimestamp))) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetVehicleVariableList?format=json";
        String response = restClient.get().uri(url).retrieve().body(String.class);
        List<VpicRow> rows = parseRows(response, "vehicle variables", "ID", "Name");
        transactionTemplate.executeWithoutResult(_ -> {
            for (VpicRow row : rows) {
                UUID id = localId("variable-", row.vpicId());
                VehicleVariable variable = matchVariable(cached, row, id);
                variable.setNhtsaId(row.vpicId());
                variable.setName(row.name() == null ? "" : row.name());
                variable.setDescription(description(row.node()));
                variable.setCacheTimestamp(LocalDateTime.now(clock));
                vehicleVariableRepository.save(variable);
            }
        });
        return vehicleVariableRepository.findAll();
    }

    @Override
    public List<VehicleVariableValue> getVehicleVariableValues(UUID variableId) {
        VehicleVariable variable = vehicleVariableRepository
                .findById(variableId)
                .orElseThrow(() -> new IllegalArgumentException("Vehicle variable not found with ID: " + variableId));
        List<VehicleVariableValue> cached = vehicleVariableValueRepository.findByVariable_Id(variableId);
        if (isCacheFresh(lastRefreshed(cached, VehicleVariableValue::getCacheTimestamp))) {
            return cached;
        }
        Long vpicVariableId = variable.getNhtsaId();
        if (vpicVariableId == null) {
            log.debug("Variable {} has no vPIC id; serving its values from cache", variableId);
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetVehicleVariableValuesList/" + vpicVariableId + FORMAT_JSON;
        String response = restClient.get().uri(url).retrieve().body(String.class);
        List<VpicRow> rows = parseRows(response, "vehicle variable values", "Id", "Name", "Value");
        transactionTemplate.executeWithoutResult(_ -> {
            for (VpicRow row : rows) {
                UUID id = localId("variable-value-" + vpicVariableId + "-", row.vpicId());
                VehicleVariableValue value = matchValue(cached, row, id);
                value.setVariable(variable);
                value.setValue(row.name() == null ? "" : row.name());
                value.setValueId(Long.toString(row.vpicId()));
                value.setCacheTimestamp(LocalDateTime.now(clock));
                vehicleVariableValueRepository.save(value);
            }
        });
        return vehicleVariableValueRepository.findByVariable_Id(variableId);
    }

    @Override
    public List<ManufacturerResponse> getManufacturers() {
        return fetchManufacturers().stream()
                .map(VehicleFitmentMapper::toManufacturerResponse)
                .toList();
    }

    /*
     * Manufacturers, makes, models and vehicle types refresh from vPIC in place (#2416): each vPIC row updates
     * the row keyed by vPIC's own id, or inserts it under an id derived from that id if new. Nothing is deleted.
     * These rows are referenced by foreign keys (make -> manufacturer, model and vehicle_type -> make,
     * part_fitment_entity -> all four), so the delete and reinsert this replaced failed on the first refresh
     * after any child existed. A row vPIC no longer returns is kept, with its children and part fitments.
     *
     * Names are unique case-insensitively (per make for models and vehicle types, per manufacturer for makes).
     * A vPIC row whose name already belongs to a different row, such as one created by a fitment request, is
     * logged and skipped rather than aborting the whole refresh.
     */
    private List<Manufacturer> fetchManufacturers() {
        List<Manufacturer> cached = manufacturerRepository.findAll();
        if (isCacheFresh(lastRefreshed(cached, Manufacturer::getCacheTimestamp))) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/getallmanufacturers?format=json";
        String response = restClient.get().uri(url).retrieve().body(String.class);
        // vPIC leaves Mfr_CommonName null or empty for many manufacturers; storing "" for each would collide
        // on ux_manufacturer_name_lower, so fall back to the legal name.
        List<VpicRow> rows = parseRows(response, "manufacturers", "Mfr_ID", "Mfr_CommonName", "Mfr_Name");
        // Upsert by the derived id; never delete (see the note above fetchManufacturers).
        transactionTemplate.executeWithoutResult(_ -> {
            for (VpicRow row : rows) {
                UUID id = localId("manufacturer-", row.vpicId());
                String name = row.name();
                if (name != null
                        && nameTakenByAnotherRow(
                                manufacturerRepository.findAllByNameIgnoreCase(name), id, Manufacturer::getId)) {
                    log.warn(
                            "Skipping vPIC manufacturer {}: name '{}' belongs to another manufacturer row",
                            row.vpicId(),
                            name);
                    continue;
                }
                Manufacturer m = manufacturerRepository.findById(id).orElseGet(Manufacturer::new);
                m.setId(id);
                m.setNhtsaId(row.vpicId());
                m.setName(name);
                m.setCacheTimestamp(LocalDateTime.now(clock));
                manufacturerRepository.save(m);
            }
        });
        return manufacturerRepository.findAll();
    }

    @Override
    public List<MakeResponse> getMakesByManufacturer(UUID manufacturerId) {
        return fetchMakesByManufacturer(manufacturerId).stream()
                .map(VehicleFitmentMapper::toMakeResponse)
                .toList();
    }

    private List<Make> fetchMakesByManufacturer(UUID manufacturerId) {
        Manufacturer manufacturer = manufacturerRepository
                .findById(manufacturerId)
                .orElseThrow(() -> new IllegalArgumentException("Manufacturer not found with ID: " + manufacturerId));
        List<Make> cached = makeRepository.findByManufacturerId(manufacturerId);
        if (isCacheFresh(lastRefreshed(cached, Make::getCacheTimestamp))) {
            return cached;
        }
        Long vpicManufacturerId = manufacturer.getNhtsaId();
        if (vpicManufacturerId == null) {
            log.debug("Manufacturer {} has no vPIC id; serving its makes from cache", manufacturerId);
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetMakeForManufacturer/" + vpicManufacturerId + FORMAT_JSON;
        String response = restClient.get().uri(url).retrieve().body(String.class);
        List<VpicRow> rows = parseRows(response, "makes", "Make_ID", "Make_Name");
        // Upsert by the derived id; never delete (see the note above fetchManufacturers).
        transactionTemplate.executeWithoutResult(_ -> {
            for (VpicRow row : rows) {
                UUID id = localId("make-", row.vpicId());
                String name = row.name();
                if (name != null
                        && makeRepository
                                .findByManufacturerIdAndNameIgnoreCase(manufacturerId, name)
                                .filter(other -> !id.equals(other.getId()))
                                .isPresent()) {
                    log.warn(
                            "Skipping vPIC make {}: name '{}' belongs to another make of manufacturer {}",
                            row.vpicId(),
                            name,
                            manufacturerId);
                    continue;
                }
                Make make = makeRepository.findById(id).orElseGet(Make::new);
                make.setId(id);
                make.setNhtsaId(row.vpicId());
                make.setName(name);
                make.setManufacturer(manufacturer);
                make.setCacheTimestamp(LocalDateTime.now(clock));
                makeRepository.save(make);
            }
        });
        return makeRepository.findByManufacturerId(manufacturerId);
    }

    @Override
    public List<ModelResponse> getModelsByMake(UUID makeId) {
        return fetchModelsByMake(makeId).stream()
                .map(VehicleFitmentMapper::toModelResponse)
                .toList();
    }

    private List<Model> fetchModelsByMake(UUID makeId) {
        Make make = makeRepository
                .findById(makeId)
                .orElseThrow(() -> new IllegalArgumentException("Make not found with ID: " + makeId));
        List<Model> cached = modelRepository.findByMakeId(makeId);
        if (isCacheFresh(lastRefreshed(cached, Model::getCacheTimestamp))) {
            return cached;
        }
        Long vpicMakeId = make.getNhtsaId();
        if (vpicMakeId == null) {
            log.debug("Make {} has no vPIC id; serving its models from cache", makeId);
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetModelsForMakeId/" + vpicMakeId + FORMAT_JSON;
        String response = restClient.get().uri(url).retrieve().body(String.class);
        List<VpicRow> rows = parseRows(response, "models", "Model_ID", "Model_Name");
        // Upsert by the derived id; never delete (see the note above fetchManufacturers).
        transactionTemplate.executeWithoutResult(_ -> {
            for (VpicRow row : rows) {
                UUID id = localId("model-", row.vpicId());
                String name = row.name();
                if (name != null
                        && modelRepository
                                .findByMakeIdAndNameIgnoreCase(makeId, name)
                                .filter(other -> !id.equals(other.getId()))
                                .isPresent()) {
                    log.warn(
                            "Skipping vPIC model {}: name '{}' belongs to another model of make {}",
                            row.vpicId(),
                            name,
                            makeId);
                    continue;
                }
                Model model = modelRepository.findById(id).orElseGet(Model::new);
                model.setId(id);
                model.setNhtsaId(row.vpicId());
                model.setName(name);
                model.setMake(make);
                model.setCacheTimestamp(LocalDateTime.now(clock));
                modelRepository.save(model);
            }
        });
        return modelRepository.findByMakeId(makeId);
    }

    @Override
    public List<VehicleTypeResponse> getVehicleTypesForMake(UUID makeId) {
        return fetchVehicleTypesForMake(makeId).stream()
                .map(VehicleFitmentMapper::toVehicleTypeResponse)
                .toList();
    }

    private List<VehicleType> fetchVehicleTypesForMake(UUID makeId) {
        Make make = makeRepository
                .findById(makeId)
                .orElseThrow(() -> new IllegalArgumentException("Make not found with ID: " + makeId));
        List<VehicleType> cached = vehicleTypeRepository.findByMakeId(makeId);
        if (isCacheFresh(lastRefreshed(cached, VehicleType::getCacheTimestamp))) {
            return cached;
        }
        Long vpicMakeId = make.getNhtsaId();
        if (vpicMakeId == null) {
            log.debug("Make {} has no vPIC id; serving its vehicle types from cache", makeId);
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetVehicleTypesForMakeId/" + vpicMakeId + FORMAT_JSON;
        String response = restClient.get().uri(url).retrieve().body(String.class);
        List<VpicRow> rows = parseRows(response, "vehicle types for make", "VehicleTypeId", "VehicleTypeName");
        // Upsert, never delete (see the note above fetchManufacturers): part_fitment_entity references
        // vehicle_type too. vPIC's VehicleTypeId is global (2 = Passenger Car) while a row here belongs to
        // one make, so the key is (make, VehicleTypeId). An existing row with that key is updated whatever
        // its id, which also adopts rows cached before ids were derived; a new one gets an id derived from
        // both vPIC ids.
        transactionTemplate.executeWithoutResult(_ -> {
            for (VpicRow row : rows) {
                String vehicleTypeId = Long.toString(row.vpicId());
                String name = row.name();
                VehicleType vt = cached.stream()
                        .filter(existing -> vehicleTypeId.equals(existing.getVehicleTypeId()))
                        .findFirst()
                        .orElseGet(() -> {
                            VehicleType created = new VehicleType();
                            created.setId(localId("vehicle-type-" + vpicMakeId + "-", row.vpicId()));
                            return created;
                        });
                UUID id = vt.getId();
                if (name != null
                        && vehicleTypeRepository
                                .findByMakeIdAndVehicleTypeNameIgnoreCase(makeId, name)
                                .filter(other -> !id.equals(other.getId()))
                                .isPresent()) {
                    log.warn(
                            "Skipping vPIC vehicle type {}: name '{}' belongs to another vehicle type of make {}",
                            vehicleTypeId,
                            name,
                            makeId);
                    continue;
                }
                vt.setMake(make);
                vt.setVehicleTypeId(vehicleTypeId);
                vt.setVehicleTypeName(name);
                vt.setCacheTimestamp(LocalDateTime.now(clock));
                vehicleTypeRepository.save(vt);
            }
        });
        return vehicleTypeRepository.findByMakeId(makeId);
    }

    /**
     * Finds the cached variable a vPIC row refreshes, in order: the row already holding that vPIC id (an adopted
     * legacy row keeps its random primary key, so the derived id alone would miss it and duplicate it), the row
     * with the derived id, then a legacy row with no vPIC id and the same name (case-insensitive). Otherwise a
     * new row under the derived id.
     */
    private static VehicleVariable matchVariable(List<VehicleVariable> cached, VpicRow row, UUID derivedId) {
        return cached.stream()
                .filter(existing -> Objects.equals(existing.getNhtsaId(), row.vpicId()))
                .findFirst()
                .or(() -> cached.stream()
                        .filter(existing -> derivedId.equals(existing.getId()))
                        .findFirst())
                .or(() -> cached.stream()
                        .filter(existing -> existing.getNhtsaId() == null
                                && row.name() != null
                                && row.name().equalsIgnoreCase(existing.getName()))
                        .findFirst())
                .orElseGet(() -> {
                    VehicleVariable created = new VehicleVariable();
                    created.setId(derivedId);
                    return created;
                });
    }

    /**
     * Finds the cached value a vPIC row refreshes, in order: the row whose {@code valueId} is that vPIC id, the
     * row with the derived id, then a legacy row. Legacy policy: values cached before vPIC ids were stored have a
     * random primary key and a blank {@code valueId} (the old parser read a {@code ValueId} field vPIC does not
     * send), so they are adopted by (variable, case-insensitive name) and keep their primary key, which part
     * fitments reference. A legacy row with a non-blank {@code valueId} that matches nothing is left alone. Once
     * adopted a row carries its vPIC id in {@code valueId}, so it is matched by the first rule from then on and
     * two payload rows can never claim the same legacy row.
     */
    private static VehicleVariableValue matchValue(List<VehicleVariableValue> cached, VpicRow row, UUID derivedId) {
        String vpicValueId = Long.toString(row.vpicId());
        return cached.stream()
                .filter(existing -> vpicValueId.equals(existing.getValueId()))
                .findFirst()
                .or(() -> cached.stream()
                        .filter(existing -> derivedId.equals(existing.getId()))
                        .findFirst())
                .or(() -> cached.stream()
                        .filter(existing -> (existing.getValueId() == null
                                        || existing.getValueId().isBlank())
                                && row.name() != null
                                && row.name().equalsIgnoreCase(existing.getValue()))
                        .findFirst())
                .orElseGet(() -> {
                    VehicleVariableValue created = new VehicleVariableValue();
                    created.setId(derivedId);
                    return created;
                });
    }

    /** One vPIC result row, validated: its numeric id and its first non-blank name, if any. */
    private record VpicRow(long vpicId, String name, JsonNode node) {}

    /**
     * Parses and validates the whole vPIC payload before anything is written, so a bad row anywhere fails the
     * refresh with nothing saved. Writing row by row as it parsed committed a partial refresh, which the
     * newest-timestamp freshness check then served as current for a day.
     */
    private List<VpicRow> parseRows(String response, String what, String idField, String... nameFields) {
        try {
            JsonNode results = objectMapper.readTree(response).get(RESULTS);
            List<VpicRow> rows = new ArrayList<>();
            for (JsonNode node : results) {
                rows.add(new VpicRow(vpicId(node, idField), firstNonBlank(node, nameFields), node));
            }
            return rows;
        } catch (Exception e) {
            throw new VehicleFitmentException("Failed to parse " + what, e);
        }
    }

    /**
     * Reads one of vPIC's numeric ids ({@code Mfr_ID}, {@code Make_ID}, {@code Model_ID},
     * {@code VehicleTypeId}). vPIC returns
     * these as JSON integers; anything else is not a payload this service understands, so it fails the
     * refresh rather than inventing an id (#2416).
     */
    private static long vpicId(JsonNode node, String field) {
        JsonNode id = node.path(field);
        if (!id.isIntegralNumber()) {
            throw new IllegalStateException("vPIC " + field + " is not an integer: " + id);
        }
        return id.asLong();
    }

    /** vPIC's variable descriptions can run past the 255-character column; keep what fits. */
    private static String description(JsonNode node) {
        String text = node.path("Description").asString("");
        return text.length() > 255 ? text.substring(0, 255) : text;
    }

    /** The first of {@code fields} holding non-blank text, trimmed; {@code null} when none does. */
    private static String firstNonBlank(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asString("").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static <T> boolean nameTakenByAnotherRow(List<T> sameName, UUID id, Function<T, UUID> idOf) {
        return sameName.stream().map(idOf).anyMatch(other -> !id.equals(other));
    }

    /**
     * When the cached rows were last refreshed from vPIC: the newest cache timestamp among them. Rows a refresh
     * no longer touches (dropped by vPIC, or created locally with no timestamp) must not make a warm cache look
     * stale, as they would if only the first row were consulted.
     */
    private static <T> LocalDateTime lastRefreshed(List<T> rows, Function<T, LocalDateTime> cacheTimestamp) {
        return rows.stream()
                .map(cacheTimestamp)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * The local id of a row cached from vPIC: a name-based UUID over vPIC's numeric id, so a refresh
     * keys the same vPIC entity to the same row. Same derivation as {@code pos-vehicle-reference-nhtsa}.
     */
    private static UUID localId(String prefix, long nhtsaId) {
        return UUID.nameUUIDFromBytes((prefix + nhtsaId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns {@code true} while the cached rows are still inside the 24-hour
     * window, i.e. while they may be served without calling vPIC. A {@code null}
     * timestamp cannot be shown to be fresh, so it is treated as needing a refetch.
     */
    private boolean isCacheFresh(LocalDateTime cacheTimestamp) {
        return cacheTimestamp != null && !cacheTimestamp.plus(CACHE_EXPIRY).isBefore(LocalDateTime.now(clock));
    }
}
