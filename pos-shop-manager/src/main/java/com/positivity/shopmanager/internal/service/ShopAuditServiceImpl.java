package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.ShopAuditEntryResponse;
import com.positivity.shopmanager.internal.dto.ShopAuditFilter;
import com.positivity.shopmanager.internal.entity.ShopAuditEntry;
import com.positivity.shopmanager.internal.enums.ShopAuditEventType;
import com.positivity.shopmanager.internal.repository.ShopAuditRepository;
import com.positivity.shopmanager.service.ShopAuditService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.positivity.shared.id.UUIDv7Generator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Service implementation for the shop audit trail.
 *
 * <p>
 * Actor identity is ALWAYS resolved from the active security context
 * (ADR-0018).
 * It is never sourced from caller-supplied parameters.
 *
 * <p>
 * Story #61 — Audit Trail for Schedule and Assignment Changes.
 */
@Service
@RequiredArgsConstructor
public class ShopAuditServiceImpl implements ShopAuditService {

    private final ShopAuditRepository shopAuditRepository;
    private final Clock clock;

    /**
     * Resolves the actor identity from the active security context.
     * ADR-0018: actor MUST come from SecurityContextHolder; never from
     * caller-supplied params.
     */
    private String resolveActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getName() != null) ? auth.getName() : "system";
    }

    @Override
    public @NonNull ShopAuditEntryResponse recordScheduleChange(
            @NonNull ShopAuditEventType eventType,
            @NonNull String appointmentId,
            String workorderId,
            String locationId,
            String changeSummary,
            String changePatch,
            String reasonCode,
            String reasonNotes) {
        UUID generatedId = UUIDv7Generator.generate();
        ShopAuditEntry entry = ShopAuditEntry.builder()
                .id(generatedId)
                .actorUserId(resolveActorId())
                .eventType(eventType)
                .appointmentId(appointmentId)
                .workorderId(workorderId)
                .locationId(locationId)
                .changeSummaryText(changeSummary)
                .changePatch(changePatch)
                .reasonCode(reasonCode)
                .reasonNotes(reasonNotes)
                .retentionYears(7)
                .build();
        shopAuditRepository.save(entry);
        return mapToResponse(entry);
    }

    @Override
    public @NonNull ShopAuditEntryResponse recordAssignmentChange(
            @NonNull ShopAuditEventType eventType,
            @NonNull String workorderId,
            @NonNull String mechanicId,
            String locationId,
            String changeSummary,
            String changePatch,
            String reasonCode,
            String reasonNotes) {
        UUID generatedId = UUIDv7Generator.generate();
        ShopAuditEntry entry = ShopAuditEntry.builder()
                .id(generatedId)
                .actorUserId(resolveActorId())
                .eventType(eventType)
                .workorderId(workorderId)
                .mechanicId(mechanicId)
                .locationId(locationId)
                .changeSummaryText(changeSummary)
                .changePatch(changePatch)
                .reasonCode(reasonCode)
                .reasonNotes(reasonNotes)
                .retentionYears(7)
                .build();
        shopAuditRepository.save(entry);
        return mapToResponse(entry);
    }

    @Override
    public @NonNull List<ShopAuditEntryResponse> search(@NonNull ShopAuditFilter filter) {
        if (!filter.hasAtLeastOneFilter()) {
            throw new IllegalArgumentException(
                    "At least one filter criterion is required for audit search");
        }
        Instant now = Instant.now(clock);
        Instant from = filter.getFromDateTime() != null
                ? filter.getFromDateTime()
                : now.minus(90, ChronoUnit.DAYS);
        Instant to = filter.getToDateTime() != null
                ? filter.getToDateTime()
                : now;
        return shopAuditRepository
                .findByFilter(
                        filter.getWorkorderId(),
                        filter.getAppointmentId(),
                        filter.getMechanicId(),
                        filter.getActorUserId(),
                        filter.getEventType(),
                        filter.getLocationId(),
                        from,
                        to)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public @NonNull Optional<ShopAuditEntryResponse> findById(@NonNull UUID id) {
        return shopAuditRepository.findById(id).map(this::mapToResponse);
    }

    private ShopAuditEntryResponse mapToResponse(ShopAuditEntry entry) {
        return ShopAuditEntryResponse.builder()
                .id(entry.getId())
                .eventType(entry.getEventType())
                .workorderId(entry.getWorkorderId())
                .appointmentId(entry.getAppointmentId())
                .mechanicId(entry.getMechanicId())
                .locationId(entry.getLocationId())
                .actorUserId(entry.getActorUserId())
                .changeSummaryText(entry.getChangeSummaryText())
                .changePatch(entry.getChangePatch())
                .reasonCode(entry.getReasonCode())
                .retentionYears(entry.getRetentionYears())
                .recordedAt(entry.getRecordedAt())
                .build();
    }
}
