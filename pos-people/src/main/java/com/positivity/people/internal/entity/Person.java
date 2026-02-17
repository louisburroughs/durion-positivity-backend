package com.positivity.people.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.*;
import java.util.List;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Person {
    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }

    private String firstName;
    private String lastName;

    private String primaryEmail;
    private String secondaryEmail;

    @ElementCollection
    private List<String> phoneNumbers;

    /** Optional, validated externally - stick with username not userName */
    private String username;
}
