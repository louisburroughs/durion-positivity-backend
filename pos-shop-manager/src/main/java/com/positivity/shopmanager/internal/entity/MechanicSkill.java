package com.positivity.shopmanager.internal.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "mechanic_skill")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MechanicSkill {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "mechanic_id", columnDefinition = "UUID")
    private UUID mechanicId;

    @Column(name = "skill_code")
    private String skillCode;

    @Column(name = "proficiency_level")
    private int proficiencyLevel;

    @Column(name = "certified_date")
    private LocalDate certifiedDate;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
