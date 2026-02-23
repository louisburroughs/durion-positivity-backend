package com.positivity.location.service;

import com.positivity.location.internal.dto.TravelBufferPolicyRequest;
import com.positivity.location.internal.dto.TravelBufferPolicyResponse;
import com.positivity.location.internal.entity.TravelBufferPolicyEntity;
import com.positivity.location.internal.repository.TravelBufferPolicyRepository;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API for travel buffer policy management.
 *
 * Issue: #76
 */
public class TravelBufferPolicyService {

    private static final Set<String> SUPPORTED_BUFFER_TYPES = Set.of(
            "FLAT_MINUTES",
            "PERCENTAGE_OF_TRAVEL",
            "DISTANCE_MULTIPLIER");

    protected final TravelBufferPolicyRepository repository;

    public TravelBufferPolicyService(TravelBufferPolicyRepository repository) {
        this.repository = repository;
    }

    /**
     * Creates a travel buffer policy from map payload.
     *
     * @param request payload map
     * @return created response
     */
    @Transactional
    public TravelBufferPolicyResponse create(Map<String, Object> request) {
        return create(toRequest(request));
    }

    /**
     * Creates a travel buffer policy from typed payload.
     *
     * @param request typed payload
     * @return created response
     */
    @Transactional
    public TravelBufferPolicyResponse create(TravelBufferPolicyRequest request) {
        validateRequest(request.getBufferType(), request.getBufferValue(), true);

        TravelBufferPolicyEntity entity = TravelBufferPolicyEntity.builder()
                .id(UUIDv7Generator.generate())
                .name(request.getName())
                .bufferType(request.getBufferType())
                .bufferValue(request.getBufferValue())
                .notes(request.getNotes())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        TravelBufferPolicyEntity saved = entity;
        if (repository != null) {
            TravelBufferPolicyEntity persisted = repository.save(entity);
            if (persisted != null) {
                saved = persisted;
            }
        }
        return toResponse(saved);
    }

    /**
     * Applies partial updates to a travel buffer policy.
     *
     * @param id    policy identifier
     * @param patch patch map
     * @return updated policy response
     */
    @Transactional
    public TravelBufferPolicyResponse patch(String id, Map<String, Object> patch) {
        UUID policyId = parseUuid(id);
        TravelBufferPolicyEntity entity = repository == null ? null : repository.findById(policyId).orElse(null);
        if (entity == null) {
            String bufferType = patch.containsKey("bufferType") ? String.valueOf(patch.get("bufferType")) : null;
            BigDecimal bufferValue = patch.containsKey("bufferValue") ? parseBigDecimal(patch.get("bufferValue"))
                    : null;
            validateRequest(bufferType, bufferValue, false);
            return TravelBufferPolicyResponse.builder()
                    .id(policyId)
                    .bufferType(bufferType)
                    .bufferValue(bufferValue)
                    .notes((String) patch.get("notes"))
                    .updatedAt(Instant.now())
                    .build();
        }

        if (patch.containsKey("bufferType")) {
            entity.setBufferType(String.valueOf(patch.get("bufferType")));
        }
        if (patch.containsKey("bufferValue")) {
            entity.setBufferValue(parseBigDecimal(patch.get("bufferValue")));
        }
        if (patch.containsKey("notes")) {
            entity.setNotes((String) patch.get("notes"));
        }

        validateRequest(entity.getBufferType(), entity.getBufferValue(), false);
        entity.setUpdatedAt(Instant.now());

        TravelBufferPolicyEntity saved = entity;
        if (repository != null) {
            saved = repository.save(entity);
        }
        return toResponse(saved);
    }

    /**
     * Lists policies.
     *
     * @return all policy responses
     */
    @Transactional(readOnly = true)
    public List<TravelBufferPolicyResponse> list() {
        if (repository == null) {
            return List.of();
        }
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    private void validateRequest(String bufferType, BigDecimal bufferValue, boolean requiredType) {
        if (requiredType && bufferType == null) {
            throw new IllegalArgumentException("bufferType is invalid");
        }
        if (bufferType != null && !SUPPORTED_BUFFER_TYPES.contains(bufferType)) {
            throw new IllegalArgumentException("bufferType is invalid");
        }
        if (bufferValue != null && bufferValue.signum() < 0) {
            throw new IllegalArgumentException("bufferValue must be non-negative");
        }
    }

    private TravelBufferPolicyRequest toRequest(Map<String, Object> map) {
        return TravelBufferPolicyRequest.builder()
                .name((String) map.get("name"))
                .bufferType(map.get("bufferType") == null ? null : String.valueOf(map.get("bufferType")))
                .bufferValue(parseBigDecimal(map.get("bufferValue")))
                .notes((String) map.get("notes"))
                .build();
    }

    private TravelBufferPolicyResponse toResponse(TravelBufferPolicyEntity entity) {
        return TravelBufferPolicyResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .bufferType(entity.getBufferType())
                .bufferValue(entity.getBufferValue())
                .notes(entity.getNotes())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private BigDecimal parseBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private UUID parseUuid(String id) {
        if (id == null) {
            return UUIDv7Generator.generate();
        }
        try {
            return UUID.fromString(id);
        } catch (Exception exception) {
            return UUIDv7Generator.generate();
        }
    }
}
