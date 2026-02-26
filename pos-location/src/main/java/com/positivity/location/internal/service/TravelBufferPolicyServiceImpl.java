package com.positivity.location.internal.service;

import com.positivity.location.service.TravelBufferPolicyService;

import com.positivity.location.internal.dto.TravelBufferPolicyRequest;
import com.positivity.location.internal.dto.TravelBufferPolicyResponse;
import com.positivity.location.internal.entity.TravelBufferPolicyEntity;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.TravelBufferPolicyRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public API for travel buffer policy management.
 *
 * Issue: #76
 */
@Service
public class TravelBufferPolicyServiceImpl implements TravelBufferPolicyService {

    private static final String TRAVEL_BUFFER_POLICY_NAME_TAKEN = "TRAVEL_BUFFER_POLICY_NAME_TAKEN";
    private static final String TRAVEL_BUFFER_POLICY_CONFLICT = "TRAVEL_BUFFER_POLICY_CONFLICT";
    private static final Set<String> SUPPORTED_BUFFER_TYPES = Set.of(
            "FLAT_MINUTES",
            "PERCENTAGE_OF_TRAVEL",
            "DISTANCE_MULTIPLIER");

    protected final TravelBufferPolicyRepository repository;

    public TravelBufferPolicyServiceImpl(TravelBufferPolicyRepository repository) {
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
                .name(request.getName())
                .bufferType(request.getBufferType())
                .bufferValue(request.getBufferValue())
                .notes(request.getNotes())
                .build();

        TravelBufferPolicyEntity saved = entity;
        if (repository != null) {
            try {
                TravelBufferPolicyEntity persisted = repository.save(entity);
                saved = persisted;

            } catch (DataIntegrityViolationException exception) {
                throw toTravelBufferPolicyConflictException(exception);
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
        UUID policyId = parseUuidStrict(id);
        TravelBufferPolicyEntity entity = repository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Travel buffer policy not found"));

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

        TravelBufferPolicyEntity saved = entity;
        if (repository != null) {
            try {
                saved = repository.save(entity);
            } catch (DataIntegrityViolationException exception) {
                throw toTravelBufferPolicyConflictException(exception);
            }
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

    private UUID parseUuidStrict(String id) {
        try {
            return UUID.fromString(id);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid id: " + id, exception);
        }
    }

    private DuplicateResourceException toTravelBufferPolicyConflictException(

            DataIntegrityViolationException exception) {
        if (isNameConstraintViolation(exception)) {
            return new DuplicateResourceException(TRAVEL_BUFFER_POLICY_NAME_TAKEN);
        }
        return new DuplicateResourceException(TRAVEL_BUFFER_POLICY_CONFLICT);
    }

    private boolean isNameConstraintViolation(Throwable throwable) {
        String details = lowerCaseMessages(throwable);
        return details.contains("travel_buffer_policies_name_key")
                || details.contains("travel_buffer_policies")
                || details.contains(" name ");
    }

    private String lowerCaseMessages(Throwable throwable) {
        StringBuilder all = new StringBuilder();
        Throwable cursor = throwable;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null) {
                all.append(message.toLowerCase(java.util.Locale.ROOT)).append(' ');
            }
            cursor = cursor.getCause();
        }
        return all.toString();
    }
}
