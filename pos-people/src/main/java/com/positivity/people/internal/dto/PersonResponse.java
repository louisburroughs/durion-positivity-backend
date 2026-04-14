package com.positivity.people.internal.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class PersonResponse {

    private UUID id;

    private String firstName;

    private String lastName;

    private String primaryEmail;

    private String secondaryEmail;

    private List<String> phoneNumbers;

    private String username;

}
