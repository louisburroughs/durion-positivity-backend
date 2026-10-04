package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.BayTypeResponse;
import com.positivity.location.internal.entity.BaySpecialtyOperationEntity;
import com.positivity.location.internal.entity.ExtCatalogServiceReplica;
import com.positivity.location.internal.enums.BayType;
import com.positivity.location.internal.repository.BaySpecialtyOperationRepository;
import com.positivity.location.internal.repository.ExtCatalogServiceReplicaRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the bay type read model from {@code bay_specialty_operation} the way
 * {@link BaySpecialtyMapPublisher} builds its fact: one entry per {@link BayType}, in enum order,
 * including a type with no rows. Both tables are tenant scoped, so row-level security confines
 * every read to the caller's tenant; a tenant whose map was never provisioned sees every type with
 * empty defaults.
 *
 * <p>A seeded code that is not an active service in the {@code ext_catalog_service} replica — the
 * same test {@link ServiceCapabilityCodeValidator} applies — is left out of the response and logged
 * as a seed defect, rather than offered to a client as a default the API would refuse from it.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class BayTypeServiceImpl implements BayTypeService {

    private final BaySpecialtyOperationRepository operationRepository;
    private final ExtCatalogServiceReplicaRepository replicaRepository;

    public BayTypeServiceImpl(
            BaySpecialtyOperationRepository operationRepository, ExtCatalogServiceReplicaRepository replicaRepository) {
        this.operationRepository = operationRepository;
        this.replicaRepository = replicaRepository;
    }

    @Override
    public @NonNull List<BayTypeResponse> listBayTypes() {
        Map<String, SortedSet<String>> codesByType = new HashMap<>();
        SortedSet<String> allCodes = new TreeSet<>();
        for (BaySpecialtyOperationEntity row : operationRepository.findAll()) {
            String code = ServiceCapabilityCodeValidator.normalize(row.getOperationCode());
            if (row.getBayType() == null || code.isBlank()) {
                log.warn(
                        "Specialty map row {} has a blank bayType or operation code; omitted from GET /v1/bay-types"
                                + " (seed defect, fix bay_specialty_operation)",
                        row.getId());
                continue;
            }
            codesByType
                    .computeIfAbsent(row.getBayType(), key -> new TreeSet<>())
                    .add(code);
            allCodes.add(code);
        }

        Map<String, ExtCatalogServiceReplica> activeByCode = activeServices(allCodes);

        List<BayTypeResponse> entries = new ArrayList<>(BayType.values().length);
        for (BayType bayType : BayType.values()) {
            SortedSet<String> seeded = codesByType.getOrDefault(bayType.name(), new TreeSet<>());
            List<String> codes = new ArrayList<>(seeded.size());
            List<BayTypeResponse.DefaultServiceEntry> services = new ArrayList<>(seeded.size());
            List<String> dropped = new ArrayList<>();
            for (String code : seeded) {
                ExtCatalogServiceReplica service = activeByCode.get(code);
                if (service == null) {
                    dropped.add(code);
                    continue;
                }
                codes.add(code);
                services.add(new BayTypeResponse.DefaultServiceEntry(code, service.getName()));
            }
            if (!dropped.isEmpty()) {
                log.warn(
                        "Specialty map for bayType {} names codes that are not active catalog operation codes;"
                                + " omitted from GET /v1/bay-types (seed defect, fix bay_specialty_operation): {}",
                        bayType,
                        String.join(", ", dropped));
            }
            entries.add(BayTypeResponse.builder()
                    .bayType(bayType)
                    .acceptsGeneralWork(bayType.acceptsGeneralWork())
                    .defaultServiceCapabilityCodes(List.copyOf(codes))
                    .defaultServices(List.copyOf(services))
                    .build());
        }
        return List.copyOf(entries);
    }

    private Map<String, ExtCatalogServiceReplica> activeServices(SortedSet<String> codes) {
        Map<String, ExtCatalogServiceReplica> byCode = new HashMap<>();
        if (codes.isEmpty()) {
            return byCode;
        }
        for (ExtCatalogServiceReplica service : replicaRepository.findByOperationCodeInAndActiveIsTrue(codes)) {
            if (service.getOperationCode() != null) {
                byCode.putIfAbsent(ServiceCapabilityCodeValidator.normalize(service.getOperationCode()), service);
            }
        }
        return byCode;
    }
}
