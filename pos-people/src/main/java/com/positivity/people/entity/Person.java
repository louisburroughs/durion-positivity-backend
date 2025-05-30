package com.positivity.people.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Person {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String firstName;
    private String lastName;

    private String primaryEmail;
    private String secondaryEmail;

    @ElementCollection
    private List<String> phoneNumbers;

    private String username; // Optional, validated externally
}

