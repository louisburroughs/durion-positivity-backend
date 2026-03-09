package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ShopAuditEntry;
import com.positivity.shopmanager.internal.enums.ShopAuditEventType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link ShopAuditEntry} records.
 * Story #61 — immutable audit trail; no delete methods are defined here.
 */
public interface ShopAuditRepository extends JpaRepository<ShopAuditEntry, UUID> {

    List<ShopAuditEntry> findByWorkorderId(String workorderId);

    List<ShopAuditEntry> findByAppointmentId(String appointmentId);

    List<ShopAuditEntry> findByMechanicId(String mechanicId);

    List<ShopAuditEntry> findByActorUserId(String actorUserId);

    List<ShopAuditEntry> findByEventType(ShopAuditEventType eventType);

    List<ShopAuditEntry> findByLocationId(String locationId);

    List<ShopAuditEntry> findByRecordedAtBetween(Instant from, Instant to);

    /**
     * Multi-dimensional filter search with mandatory date range.
     * Null dimension parameters are ignored (treated as wildcard).
     * Story #61 / RQ5 — prevents unbounded full-table scans.
     */
    @Query("SELECT e FROM ShopAuditEntry e WHERE " +
            "(:workorderId IS NULL OR e.workorderId = :workorderId) AND " +
            "(:appointmentId IS NULL OR e.appointmentId = :appointmentId) AND " +
            "(:mechanicId IS NULL OR e.mechanicId = :mechanicId) AND " +
            "(:actorUserId IS NULL OR e.actorUserId = :actorUserId) AND " +
            "(:eventType IS NULL OR e.eventType = :eventType) AND " +
            "(:locationId IS NULL OR e.locationId = :locationId) AND " +
            "e.recordedAt BETWEEN :from AND :to " +
            "ORDER BY e.recordedAt DESC")
    List<ShopAuditEntry> findByFilter(
            @Param("workorderId") String workorderId,
            @Param("appointmentId") String appointmentId,
            @Param("mechanicId") String mechanicId,
            @Param("actorUserId") String actorUserId,
            @Param("eventType") ShopAuditEventType eventType,
            @Param("locationId") String locationId,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
