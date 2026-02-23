package com.positivity.location.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Id;
import lombok.*;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(uniqueConstraints = {
        @UniqueConstraint(columnNames = { "child_id", "parent_type" }),
        @UniqueConstraint(columnNames = { "child_id", "parent_id" })
})
public class LocationParent {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Location parent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "child_id")
    private Location child;

    @Enumerated(EnumType.STRING)
    @Column(name = "parent_type", nullable = false)
    private ParentType parentType;
}
