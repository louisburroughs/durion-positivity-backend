package com.positivity.nhtsa.internal.service;

import com.positivity.nhtsa.internal.entity.*;
import com.positivity.nhtsa.internal.exception.CarApiException;
import com.positivity.nhtsa.internal.repository.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@RequiredArgsConstructor
@Service
public class VehicleReferenceService {
    private static final String RESULTS = "Results";

    private static final String JSON_FORMAT_QUERY = "?format=json";

    private final Clock clock;

    private static final Duration CACHE_EXPIRY = Duration.ofHours(24);
    /**
     * vPIC API base. Must be {@code /api/vehicles} — this previously read
     * {@code /v1/vehicles}, which is not a vPIC path, so every call below returned 404 and this
     * module had never run against live vPIC. Verified against
     * {@code https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVin/5UXWX7C5*BA?format=xml&modelyear=2011}.
     */
    private static final String NHTSA_API_BASE = "https://vpic.nhtsa.dot.gov/api/vehicles";

    private final ManufacturerRepository manufacturerRepository;
    private final MakeRepository makeRepository;
    private final ModelRepository modelRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final RestClient restClient;
    private final VehicleVariableRepository vehicleVariableRepository;
    private final VehicleVariableValueRepository vehicleVariableValueRepository;
    /** Writes a refresh in one transaction, opened only after the vPIC call has returned and parsed. */
    private final TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /*
     * Every refresh upserts, never deletes (#2454): the rows reference one another by foreign key (make ->
     * manufacturer, model and vehicle_type -> make, vehicle_variable_value -> vehicle_variable), so the delete and
     * reinsert this replaced failed on the first repeat refresh once a child existed. A row vPIC no longer returns
     * is kept. The whole vPIC payload is parsed and validated before the single transaction that writes it, so a
     * bad row anywhere fails the refresh with nothing saved. Dependent vPIC URLs are built from the vPIC id stored
     * on the parent row, never from its local UUID; a parent with no vPIC id is served from cache.
     */
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
                variable.setDescription(truncate(row.node().path("Description").asString("")));
                variable.setCacheTimestamp(LocalDateTime.now(clock));
                vehicleVariableRepository.save(variable);
            }
        });
        return vehicleVariableRepository.findAll();
    }

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
        String url = NHTSA_API_BASE + "/GetVehicleVariableValuesList/" + vpicVariableId + JSON_FORMAT_QUERY;
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

    public List<Manufacturer> getManufacturers() {
        List<Manufacturer> cached = manufacturerRepository.findAll();
        if (isCacheFresh(lastRefreshed(cached, Manufacturer::getCacheTimestamp))) {
            return cached;
        }
        String url = NHTSA_API_BASE + "/getallmanufacturers?format=json";
        String response = restClient.get().uri(url).retrieve().body(String.class);
        List<VpicRow> rows = parseRows(response, "manufacturers", "Mfr_ID", "Mfr_CommonName", "Mfr_Name");
        transactionTemplate.executeWithoutResult(_ -> {
            for (VpicRow row : rows) {
                UUID id = localId("manufacturer-", row.vpicId());
                Manufacturer m = manufacturerRepository.findById(id).orElseGet(Manufacturer::new);
                m.setId(id);
                m.setNhtsaId(row.vpicId());
                m.setName(row.name() == null ? "" : row.name());
                m.setCacheTimestamp(LocalDateTime.now(clock));
                manufacturerRepository.save(m);
            }
        });
        return manufacturerRepository.findAll();
    }

    public List<Make> getMakesByManufacturer(UUID manufacturerId) {
        Manufacturer manufacturer = manufacturerRepository
                .findById(manufacturerId)
                .orElseThrow(() -> new IllegalArgumentException("Manufacturer not found with ID: " + manufacturerId));
        List<Make> cached = makeRepository.findByManufacturersId(manufacturerId);
        if (isCacheFresh(manufacturer.getMakesRefreshedAt())) {
            return cached;
        }
        Long vpicManufacturerId = manufacturer.getNhtsaId();
        if (vpicManufacturerId == null) {
            log.debug("Manufacturer {} has no vPIC id; serving its makes from cache", manufacturerId);
            return cached;
        }
        String url = NHTSA_API_BASE + "/GetMakeForManufacturer/" + vpicManufacturerId + JSON_FORMAT_QUERY;
        String response = restClient.get().uri(url).retrieve().body(String.class);
        List<VpicRow> rows = parseRows(response, "makes", "Make_ID", "Make_Name");
        transactionTemplate.executeWithoutResult(_ -> {
            for (VpicRow row : rows) {
                UUID id = localId("make-", row.vpicId());
                String name = row.name() == null ? "" : row.name();
                LocalDateTime now = LocalDateTime.now(clock);
                Make make = findMake(id, row.vpicId()).orElse(null);
                if (make == null) {
                    // Atomic insert: a concurrent refresh of another manufacturer sharing this Make_ID is a no-op
                    // here, not an exception that would mark this transaction rollback-only. Read the row back.
                    makeRepository.insertIgnoringConflict(id, row.vpicId(), name, now);
                    make = findMake(id, row.vpicId())
                            .orElseThrow(
                                    () -> new IllegalStateException("Make vanished after insert: " + row.vpicId()));
                }
                make.setNhtsaId(row.vpicId());
                make.setName(name);
                make.setCacheTimestamp(now);
                makeRepository.save(make);
                // Add this manufacturer's link; never remove another manufacturer's (#2471).
                makeRepository.insertLinkIgnoringConflict(make.getId(), manufacturerId);
            }
            manufacturerRepository.findById(manufacturerId).ifPresent(m -> {
                m.setMakesRefreshedAt(LocalDateTime.now(clock));
                manufacturerRepository.save(m);
            });
        });
        return makeRepository.findByManufacturersId(manufacturerId);
    }

    /** The row for a vPIC make: by derived id, else by Make_ID (a row stored under another id). */
    private java.util.Optional<Make> findMake(UUID id, long vpicId) {
        return makeRepository.findById(id).or(() -> makeRepository.findByNhtsaId(vpicId));
    }

    public List<Model> getModelsByMake(UUID makeId) {
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
        String url = NHTSA_API_BASE + "/GetModelsForMakeId/" + vpicMakeId + JSON_FORMAT_QUERY;
        String response = restClient.get().uri(url).retrieve().body(String.class);
        List<VpicRow> rows = parseRows(response, "models", "Model_ID", "Model_Name");
        transactionTemplate.executeWithoutResult(_ -> {
            for (VpicRow row : rows) {
                UUID id = localId("model-", row.vpicId());
                Model model = modelRepository.findById(id).orElseGet(Model::new);
                model.setId(id);
                model.setNhtsaId(row.vpicId());
                model.setName(row.name() == null ? "" : row.name());
                model.setMake(make);
                model.setCacheTimestamp(LocalDateTime.now(clock));
                modelRepository.save(model);
            }
        });
        return modelRepository.findByMakeId(makeId);
    }

    public List<VehicleType> getVehicleTypesForMake(UUID makeId) {
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
        String url = NHTSA_API_BASE + "/GetVehicleTypesForMakeId/" + vpicMakeId + JSON_FORMAT_QUERY;
        String response = restClient.get().uri(url).retrieve().body(String.class);
        List<VpicRow> rows = parseRows(response, "vehicle types for make", "VehicleTypeId", "VehicleTypeName");
        // vPIC's VehicleTypeId is global (2 = Passenger Car) while a row here belongs to one make, so the key is
        // (make, VehicleTypeId). An existing row with that key is updated whatever its id, which also adopts rows
        // cached before ids were derived; a new one gets an id derived from both vPIC ids.
        transactionTemplate.executeWithoutResult(_ -> {
            for (VpicRow row : rows) {
                String vehicleTypeId = Long.toString(row.vpicId());
                VehicleType vt = cached.stream()
                        .filter(existing -> vehicleTypeId.equals(existing.getVehicleTypeId()))
                        .findFirst()
                        .orElseGet(() -> {
                            VehicleType created = new VehicleType();
                            created.setId(localId("vehicle-type-" + vpicMakeId + "-", row.vpicId()));
                            return created;
                        });
                vt.setMake(make);
                vt.setVehicleTypeId(vehicleTypeId);
                vt.setVehicleTypeName(row.name() == null ? "" : row.name());
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

    /** One vPIC result row, validated: its numeric id, its first non-blank name if any, and the raw node. */
    private record VpicRow(long vpicId, String name, JsonNode node) {}

    /** Parses and validates the whole payload before anything is written. vPIC ids must be JSON integers. */
    private List<VpicRow> parseRows(String response, String what, String idField, String... nameFields) {
        try {
            JsonNode results = objectMapper.readTree(response).get(RESULTS);
            List<VpicRow> rows = new ArrayList<>();
            for (JsonNode node : results) {
                JsonNode id = node.path(idField);
                if (!id.isIntegralNumber()) {
                    throw new IllegalStateException("vPIC " + idField + " is not an integer: " + id);
                }
                rows.add(new VpicRow(id.asLong(), firstNonBlank(node, nameFields), node));
            }
            return rows;
        } catch (Exception e) {
            throw new CarApiException("Failed to parse " + what, e);
        }
    }

    private static String firstNonBlank(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asString("").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** vPIC's variable descriptions can run past the 255-character column; keep what fits. */
    private static String truncate(String text) {
        return text.length() > 255 ? text.substring(0, 255) : text;
    }

    /** The local id of a row cached from vPIC: a name-based UUID over vPIC's numeric id. */
    private static UUID localId(String prefix, long nhtsaId) {
        return UUID.nameUUIDFromBytes((prefix + nhtsaId).getBytes(StandardCharsets.UTF_8));
    }

    /** The newest cache timestamp among the rows: untouched rows must not make a warm cache look stale. */
    private static <T> LocalDateTime lastRefreshed(List<T> rows, Function<T, LocalDateTime> cacheTimestamp) {
        return rows.stream()
                .map(cacheTimestamp)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
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
