package com.positivity.people.internal.dto;

import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

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
