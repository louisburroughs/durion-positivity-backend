package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;
@Getter
@Setter
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
public class Subcategory {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    private String name;
}
